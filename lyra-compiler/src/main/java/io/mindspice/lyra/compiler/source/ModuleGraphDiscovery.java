package io.mindspice.lyra.compiler.source;

import io.mindspice.lyra.compiler.ast.SyntaxNode;
import io.mindspice.lyra.compiler.ast.SyntaxProgram;
import io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes;
import io.mindspice.lyra.compiler.diagnostic.Diagnostic;
import io.mindspice.lyra.compiler.diagnostic.PhaseResult;
import io.mindspice.lyra.compiler.diagnostic.RelatedSpan;
import io.mindspice.lyra.compiler.grammar.GrammarMatcher;
import io.mindspice.lyra.compiler.grammar.GrammarProgram;
import io.mindspice.lyra.compiler.lex.LexedSource;
import io.mindspice.lyra.compiler.lex.Lexer;
import io.mindspice.lyra.compiler.parse.Parser;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;

/**
 * Resolves and parses the complete source graph reachable from one root.
 * Mutable maps and queues are confined to one discovery operation; only the
 * frozen {@link ModuleGraph} crosses the phase boundary.
 */
public final class ModuleGraphDiscovery {
    public static final String SOURCE_EXTENSION = ".lyra";

    private final SourceConfiguration configuration;
    private final Optional<SourceId> forbiddenImportedIdentity;

    public ModuleGraphDiscovery(SourceConfiguration configuration) {
        this(configuration, Optional.empty());
    }

    private ModuleGraphDiscovery(
            SourceConfiguration configuration, Optional<SourceId> forbiddenImportedIdentity) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.forbiddenImportedIdentity = Objects.requireNonNull(
                forbiddenImportedIdentity, "forbiddenImportedIdentity");
    }

    public static PhaseResult<ModuleGraph> discover(
            SyntaxProgram rootProgram,
            SourceSnapshot rootSnapshot,
            SourceConfiguration configuration) {
        return new ModuleGraphDiscovery(configuration).discover(rootProgram, rootSnapshot);
    }

    /** Discovers a session submission while rejecting imports that reuse its root identity. */
    public static PhaseResult<ModuleGraph> discoverSession(
            SyntaxProgram rootProgram,
            SourceSnapshot rootSnapshot,
            SourceConfiguration configuration) {
        Objects.requireNonNull(rootSnapshot, "rootSnapshot");
        return new ModuleGraphDiscovery(configuration, Optional.of(rootSnapshot.sourceId()))
                .discover(rootProgram, rootSnapshot);
    }

    public static PhaseResult<ModuleGraph> discover(
            Path root,
            SourceConfiguration configuration) {
        return new ModuleGraphDiscovery(configuration).discover(root);
    }

    public static PhaseResult<ModuleGraph> discover(
            LogicalModuleId root,
            SourceConfiguration configuration) {
        return new ModuleGraphDiscovery(configuration).discover(root);
    }

    public static PhaseResult<ModuleGraph> discover(
            String logicalRoot,
            SourceConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        try {
            return discover(LogicalModuleId.parse(logicalRoot), configuration);
        } catch (IllegalArgumentException exception) {
            return failure(Diagnostic.error(
                    CompilerDiagnosticCodes.MODULE_INVALID_IMPORT_PATH,
                    configurationSpan(),
                    "invalid logical root: " + exception.getMessage()));
        }
    }

    public static PhaseResult<ModuleGraph> discoverPath(
            Path root,
            List<Path> sourceRoots,
            List<SourceResolver> resolvers) {
        return discover(root, SourceConfiguration.ofPaths(sourceRoots, resolvers));
    }

    public static PhaseResult<ModuleGraph> discoverLogical(
            String root,
            List<Path> sourceRoots,
            List<SourceResolver> resolvers) {
        return discover(root, SourceConfiguration.ofPaths(sourceRoots, resolvers));
    }

    public PhaseResult<ModuleGraph> discover(
            SyntaxProgram rootProgram,
            SourceSnapshot rootSnapshot) {
        Objects.requireNonNull(rootProgram, "rootProgram");
        Objects.requireNonNull(rootSnapshot, "rootSnapshot");
        Discovery discovery = new Discovery(configuration, forbiddenImportedIdentity);

        PhaseResult<Discovery.CanonicalRoots> roots = discovery.validateRoots(null);
        if (roots instanceof PhaseResult.Failure<?> failure) {
            return PhaseResult.failure(failure.diagnostics());
        }
        if (!rootSnapshot.sourceId().equals(rootProgram.sourceId())) {
            return failure(Diagnostic.error(
                    CompilerDiagnosticCodes.MODULE_INVALID_CONFIGURATION,
                    rootProgram.span(),
                    "the root syntax program and source snapshot have different source identities"));
        }
        if (IntrinsicModule.claimsReservedIdentity(
                rootSnapshot.sourceId(), rootSnapshot.physicalKey())) {
            return failure(disallowedIntrinsic(rootProgram.span()));
        }
        if (!rootProgram.sourceRevision().isEmpty()
                && !rootProgram.sourceRevision().equals(rootSnapshot.sha256())) {
            return failure(Diagnostic.error(
                    CompilerDiagnosticCodes.MODULE_INVALID_CONFIGURATION,
                    rootProgram.span(),
                    "the root syntax program revision does not match its source snapshot"));
        }
        try {
            rootSnapshot.validateSpan(rootProgram.span());
        } catch (IllegalArgumentException exception) {
            return failure(Diagnostic.error(
                    CompilerDiagnosticCodes.MODULE_INVALID_CONFIGURATION,
                    rootProgram.span(),
                    "the root syntax program span does not fit its source snapshot"));
        }

        Optional<LogicalModuleId> logical = implicitLogicalId(rootSnapshot.sourceId());
        if (logical.isPresent() && logical.orElseThrow().isStdIo()) {
            return failure(disallowedIntrinsic(rootProgram.span()));
        }
        discovery.seedRoot(rootProgram, rootSnapshot, logical);
        return discovery.finish();
    }

    public PhaseResult<ModuleGraph> discover(Path root) {
        Objects.requireNonNull(root, "root");
        Discovery discovery = new Discovery(configuration, forbiddenImportedIdentity);
        Path lexicalRoot = root.toAbsolutePath().normalize();

        PhaseResult<Discovery.CanonicalRoots> rootsResult =
                discovery.validateRoots(lexicalRoot.getParent());
        if (rootsResult instanceof PhaseResult.Failure<?> failure) {
            return PhaseResult.failure(failure.diagnostics());
        }
        List<Discovery.CanonicalRoot> roots =
                ((PhaseResult.Success<Discovery.CanonicalRoots>) rootsResult).value().roots();

        if (!Files.isRegularFile(lexicalRoot)) {
            return failure(Diagnostic.error(
                    CompilerDiagnosticCodes.RESOLVE_INVALID_ROOT,
                    configurationSpan(),
                    "root source is not an existing regular .lyra file: " + lexicalRoot));
        }
        if (!lexicalRoot.getFileName().toString().endsWith(SOURCE_EXTENSION)) {
            return failure(Diagnostic.error(
                    CompilerDiagnosticCodes.RESOLVE_INVALID_ROOT,
                    configurationSpan(),
                    "root source must use the " + SOURCE_EXTENSION + " extension: " + lexicalRoot));
        }

        RootIdentity identity = rootIdentity(lexicalRoot, roots);
        if (identity.diagnostic() != null) {
            return failure(identity.diagnostic());
        }

        PhysicalSourceKey physicalKey;
        try {
            physicalKey = PhysicalSourceKey.from(lexicalRoot);
        } catch (IOException exception) {
            return failure(Diagnostic.error(
                    CompilerDiagnosticCodes.RESOLVE_SOURCE_IO,
                    identity.sourceIdSpan(),
                    "cannot canonicalize root source " + lexicalRoot + ": " + exception.getMessage()));
        }

        byte[] bytes;
        try {
            bytes = discovery.bytesFor(physicalKey, lexicalRoot);
        } catch (IOException exception) {
            return failure(Diagnostic.error(
                    CompilerDiagnosticCodes.RESOLVE_SOURCE_IO,
                    identity.sourceIdSpan(),
                    "cannot read root source " + lexicalRoot + ": " + exception.getMessage()));
        }

        PhaseResult<SourceSnapshot> snapshotResult = SourceSnapshot.capture(
                identity.sourceId(), physicalKey, bytes);
        if (snapshotResult instanceof PhaseResult.Failure<?> failure) {
            return PhaseResult.failure(failure.diagnostics());
        }
        SourceSnapshot snapshot = ((PhaseResult.Success<SourceSnapshot>) snapshotResult).value();
        PhaseResult<SyntaxProgram> syntaxResult = discovery.parse(snapshot);
        if (syntaxResult instanceof PhaseResult.Failure<?> failure) {
            return PhaseResult.failure(failure.diagnostics());
        }
        SyntaxProgram program = ((PhaseResult.Success<SyntaxProgram>) syntaxResult).value();
        Optional<LogicalModuleId> logical = implicitLogicalId(identity.sourceId());
        if (logical.isPresent() && logical.orElseThrow().isStdIo()) {
            return failure(disallowedIntrinsic(program.span()));
        }
        discovery.seedRoot(program, snapshot, logical);
        return discovery.finish();
    }

    public PhaseResult<ModuleGraph> discover(LogicalModuleId root) {
        Objects.requireNonNull(root, "root");
        if (root.isStdIo()) {
            return failure(disallowedIntrinsic(configurationSpan()));
        }
        Discovery discovery = new Discovery(configuration, forbiddenImportedIdentity);
        PhaseResult<Discovery.CanonicalRoots> rootsResult = discovery.validateRoots(null);
        if (rootsResult instanceof PhaseResult.Failure<?> failure) {
            return PhaseResult.failure(failure.diagnostics());
        }

        Discovery.Selection selection = discovery.resolve(root, configurationSpan());
        if (selection.diagnostic() != null) {
            return failure(selection.diagnostic());
        }
        Discovery.Materialized materialized = discovery.materialize(selection.source());
        if (materialized.diagnostic() != null) {
            return failure(materialized.diagnostic());
        }
        discovery.seedRoot(
                materialized.program(),
                materialized.snapshot(),
                Optional.of(root));
        return discovery.finish();
    }

    private static Optional<LogicalModuleId> implicitLogicalId(SourceId sourceId) {
        if (sourceId.isPath() && sourceId.value().endsWith(SOURCE_EXTENSION)) {
            try {
                return Optional.of(LogicalModuleId.fromSourceId(sourceId));
            } catch (IllegalArgumentException ignored) {
                // A resolver URI or an unusual root identity has no implicit logical name.
            }
        }
        return Optional.empty();
    }

    private static RootIdentity rootIdentity(
            Path lexicalRoot, List<Discovery.CanonicalRoot> roots) {
        List<Discovery.CanonicalRoot> containing = roots.stream()
                .filter(root -> lexicalRoot.startsWith(root.path()))
                .toList();
        if (containing.isEmpty()) {
            try {
                Path realRoot = lexicalRoot.toRealPath();
                containing = roots.stream()
                        .filter(root -> realRoot.startsWith(root.path()))
                        .toList();
                if (containing.size() == 1) {
                    Path relative = containing.getFirst().path().relativize(realRoot);
                    return RootIdentity.success(SourceId.path(toPosix(relative)));
                }
            } catch (IOException ignored) {
                // The read/canonicalization diagnostic below is more useful to callers.
            }
        }
        if (containing.size() != 1) {
            String message = containing.isEmpty()
                    ? "root source is outside all configured source roots: " + lexicalRoot
                    : "root source is covered by multiple configured source roots: " + lexicalRoot;
            return RootIdentity.failure(Diagnostic.error(
                    CompilerDiagnosticCodes.RESOLVE_INVALID_ROOT,
                    configurationSpan(),
                    message));
        }
        Path relative = containing.getFirst().path().relativize(lexicalRoot);
        String relativePath = toPosix(relative);
        if (relativePath.isEmpty() || !relativePath.endsWith(SOURCE_EXTENSION)) {
            return RootIdentity.failure(Diagnostic.error(
                    CompilerDiagnosticCodes.RESOLVE_INVALID_ROOT,
                    configurationSpan(),
                    "root source has no stable relative .lyra identity: " + lexicalRoot));
        }
        try {
            return RootIdentity.success(SourceId.path(relativePath));
        } catch (IllegalArgumentException exception) {
            return RootIdentity.failure(Diagnostic.error(
                    CompilerDiagnosticCodes.RESOLVE_INVALID_ROOT,
                    configurationSpan(),
                    "root source has an invalid stable identity: " + exception.getMessage()));
        }
    }

    private static String toPosix(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static Diagnostic disallowedIntrinsic(io.mindspice.lyra.compiler.source.SourceSpan span) {
        return Diagnostic.error(
                CompilerDiagnosticCodes.RESOLVE_INTRINSIC_RESERVED,
                span,
                "the intrinsic module " + LogicalModuleId.STD_IO
                        + " is reserved and cannot be supplied by user source");
    }

    private static io.mindspice.lyra.compiler.source.SourceSpan configurationSpan() {
        return io.mindspice.lyra.compiler.source.SourceSpan.at(
                SourceId.uri(URI.create("lyra:configuration")), 0);
    }

    private static PhaseResult<ModuleGraph> failure(Diagnostic diagnostic) {
        return PhaseResult.failure(diagnostic);
    }

    private record RootIdentity(SourceId sourceId, Diagnostic diagnostic) {
        private static RootIdentity success(SourceId sourceId) {
            return new RootIdentity(sourceId, null);
        }

        private static RootIdentity failure(Diagnostic diagnostic) {
            return new RootIdentity(null, diagnostic);
        }

        private io.mindspice.lyra.compiler.source.SourceSpan sourceIdSpan() {
            return diagnostic == null
                    ? io.mindspice.lyra.compiler.source.SourceSpan.at(sourceId, 0)
                    : configurationSpan();
        }
    }

    private static final class Discovery {
        private static final Comparator<Draft> DRAFT_ORDER =
                Comparator.comparing((Draft draft) -> draft.moduleId.value())
                        .thenComparing(draft -> draft.moduleId.isUri() ? 1 : 0);
        private static final Comparator<LogicalModuleId> LOGICAL_ORDER = Comparator.naturalOrder();

        private final SourceConfiguration configuration;
        private final Optional<SourceId> forbiddenImportedIdentity;
        private final Map<PhysicalSourceKey, byte[]> bytesByPhysical = new HashMap<>();
        private final Map<PhysicalSourceKey, SourceSnapshot> snapshotsByPhysical = new HashMap<>();
        private final Map<PhysicalSourceKey, SyntaxProgram> programsByPhysical = new HashMap<>();
        private final Map<LogicalModuleId, ResolvedSource> resolvedByLogical = new HashMap<>();
        private final Map<ModuleId, Draft> draftsByModule = new LinkedHashMap<>();
        private final Map<PhysicalSourceKey, Draft> draftsByPhysical = new HashMap<>();
        private final Map<LogicalModuleId, ModuleId> modulesByLogical = new HashMap<>();
        private final PriorityQueue<Draft> pending = new PriorityQueue<>(DRAFT_ORDER);
        private final List<ModuleGraph.Edge> edges = new ArrayList<>();
        private List<CanonicalRoot> roots = List.of();
        private ModuleId rootModule;

        private Discovery(SourceConfiguration configuration, Optional<SourceId> forbiddenImportedIdentity) {
            this.configuration = Objects.requireNonNull(configuration, "configuration");
            this.forbiddenImportedIdentity = Objects.requireNonNull(
                    forbiddenImportedIdentity, "forbiddenImportedIdentity");
        }

        private PhaseResult<CanonicalRoots> validateRoots(Path implicitRoot) {
            List<SourceRoot> configured = new ArrayList<>(configuration.sourceRoots());
            if (configured.isEmpty() && implicitRoot != null) {
                configured = new ArrayList<>(List.of(new SourceRoot(implicitRoot)));
            }
            configured.sort(Comparator.comparing(sourceRoot -> sourceRoot.path().toString()));
            Map<Path, CanonicalRoot> unique = new HashMap<>();
            for (SourceRoot sourceRoot : configured) {
                Path path = sourceRoot.path();
                if (!Files.exists(path)) {
                    return PhaseResult.failure(Diagnostic.error(
                            CompilerDiagnosticCodes.MODULE_INVALID_CONFIGURATION,
                            configurationSpan(),
                            "configured source root does not exist: " + path));
                }
                if (!Files.isDirectory(path)) {
                    return PhaseResult.failure(Diagnostic.error(
                            CompilerDiagnosticCodes.MODULE_INVALID_CONFIGURATION,
                            configurationSpan(),
                            "configured source root is not a directory: " + path));
                }
                try {
                    Path canonical = sourceRoot.canonicalPath();
                    unique.putIfAbsent(canonical, new CanonicalRoot(canonical));
                } catch (IOException exception) {
                    return PhaseResult.failure(Diagnostic.error(
                            CompilerDiagnosticCodes.MODULE_INVALID_CONFIGURATION,
                            configurationSpan(),
                            "cannot canonicalize configured source root " + path
                                    + ": " + exception.getMessage()));
                }
            }
            roots = unique.values().stream()
                    .sorted(Comparator.comparing(root -> root.path().toString()))
                    .toList();
            return PhaseResult.success(new CanonicalRoots(roots));
        }

        private void seedRoot(
                SyntaxProgram program,
                SourceSnapshot snapshot,
                Optional<LogicalModuleId> logicalModule) {
            ModuleId moduleId = ModuleId.fromSourceId(snapshot.sourceId());
            Draft draft = new Draft(moduleId, logicalModule, snapshot, program);
            draftsByModule.put(moduleId, draft);
            draftsByPhysical.put(snapshot.physicalKey(), draft);
            logicalModule.ifPresent(logical -> modulesByLogical.put(logical, moduleId));
            rootModule = moduleId;
            bytesByPhysical.put(snapshot.physicalKey(), snapshot.capturedUtf8Bytes());
            snapshotsByPhysical.put(snapshot.physicalKey(), snapshot);
            programsByPhysical.put(snapshot.physicalKey(), program);
            pending.add(draft);
        }

        private byte[] bytesFor(PhysicalSourceKey physicalKey, Path path) throws IOException {
            byte[] cached = bytesByPhysical.get(physicalKey);
            if (cached != null) {
                return cached.clone();
            }
            byte[] captured = Files.readAllBytes(path);
            bytesByPhysical.put(physicalKey, captured.clone());
            return captured;
        }

        private PhaseResult<SyntaxProgram> parse(SourceSnapshot snapshot) {
            SyntaxProgram cached = programsByPhysical.get(snapshot.physicalKey());
            if (cached != null) {
                return PhaseResult.success(cached);
            }
            PhaseResult<LexedSource> lexed = Lexer.lex(snapshot);
            if (lexed instanceof PhaseResult.Failure<?> failure) {
                return PhaseResult.failure(failure.diagnostics());
            }
            LexedSource lexedSource = ((PhaseResult.Success<LexedSource>) lexed).value();
            PhaseResult<GrammarProgram> grammar = GrammarMatcher.match(lexedSource);
            if (grammar instanceof PhaseResult.Failure<?> failure) {
                return PhaseResult.failure(failure.diagnostics());
            }
            GrammarProgram grammarProgram = ((PhaseResult.Success<GrammarProgram>) grammar).value();
            PhaseResult<SyntaxProgram> parsed = Parser.parse(lexedSource, grammarProgram);
            if (parsed instanceof PhaseResult.Failure<?> failure) {
                return PhaseResult.failure(failure.diagnostics());
            }
            SyntaxProgram program = ((PhaseResult.Success<SyntaxProgram>) parsed).value();
            snapshotsByPhysical.putIfAbsent(snapshot.physicalKey(), snapshot);
            programsByPhysical.put(snapshot.physicalKey(), program);
            return PhaseResult.success(program);
        }

        private Selection resolve(
                LogicalModuleId logicalModule,
                io.mindspice.lyra.compiler.source.SourceSpan importSpan) {
            Objects.requireNonNull(logicalModule, "logicalModule");
            Objects.requireNonNull(importSpan, "importSpan");
            if (logicalModule.isStdIo()) {
                return Selection.success(IntrinsicModule.stdIo().resolvedSource());
            }
            ResolvedSource cached = resolvedByLogical.get(logicalModule);
            if (cached != null) {
                return Selection.success(cached);
            }

            List<ResolvedSource> candidates = new ArrayList<>();
            for (CanonicalRoot root : roots) {
                Path candidatePath = root.path().resolve(logicalModule.relativeSourcePath()).normalize();
                if (!candidatePath.startsWith(root.path())
                        || !Files.isRegularFile(candidatePath)) {
                    continue;
                }
                PhysicalSourceKey physicalKey;
                try {
                    physicalKey = PhysicalSourceKey.from(candidatePath);
                } catch (IOException exception) {
                    return Selection.failure(Diagnostic.error(
                            CompilerDiagnosticCodes.RESOLVE_SOURCE_IO,
                            importSpan,
                            "cannot canonicalize resolved source " + candidatePath
                                    + ": " + exception.getMessage()));
                }
                byte[] bytes;
                try {
                    bytes = bytesFor(physicalKey, candidatePath);
                } catch (IOException exception) {
                    return Selection.failure(Diagnostic.error(
                            CompilerDiagnosticCodes.RESOLVE_SOURCE_IO,
                            importSpan,
                            "cannot read resolved source " + candidatePath
                                    + ": " + exception.getMessage()));
                }
                candidates.add(new ResolvedSource(
                        logicalModule,
                        SourceId.path(logicalModule.relativeSourcePath()),
                        physicalKey,
                        bytes));
            }

            for (SourceResolver resolver : configuration.resolvers()) {
                Optional<ResolvedSource> result = resolver.resolve(logicalModule);
                if (result == null) {
                    return Selection.failure(Diagnostic.error(
                            CompilerDiagnosticCodes.RESOLVE_INVALID_CANDIDATE,
                            importSpan,
                            "source resolver returned null instead of Optional for " + logicalModule));
                }
                if (result.isPresent()) {
                    ResolvedSource candidate = result.orElseThrow();
                    if (IntrinsicModule.claimsReservedIdentity(
                            candidate.sourceId(), candidate.physicalKey())) {
                        return Selection.failure(disallowedIntrinsic(importSpan));
                    }
                    if (!candidate.logicalModule().equals(logicalModule)) {
                        return Selection.failure(Diagnostic.error(
                                CompilerDiagnosticCodes.RESOLVE_INVALID_CANDIDATE,
                                candidate.sourceId().isUri()
                                        ? io.mindspice.lyra.compiler.source.SourceSpan.at(candidate.sourceId(), 0)
                                        : importSpan,
                                "source resolver returned " + candidate.logicalModule()
                                        + " while resolving " + logicalModule));
                    }
                    candidates.add(candidate);
                }
            }

            Selection selected = select(logicalModule, importSpan, candidates);
            if (selected.diagnostic() == null) {
                resolvedByLogical.put(logicalModule, selected.source());
            }
            return selected;
        }

        private Selection select(
                LogicalModuleId logicalModule,
                io.mindspice.lyra.compiler.source.SourceSpan importSpan,
                List<ResolvedSource> candidates) {
            if (candidates.isEmpty()) {
                return Selection.failure(Diagnostic.error(
                        CompilerDiagnosticCodes.RESOLVE_MISSING_MODULE,
                        importSpan,
                        "no source satisfies logical module " + logicalModule));
            }

            Map<PhysicalSourceKey, List<ResolvedSource>> byPhysical = new HashMap<>();
            List<ResolvedSource> orderedCandidates = candidates.stream()
                    .sorted(candidateComparator())
                    .toList();
            for (ResolvedSource candidate : orderedCandidates) {
                List<ResolvedSource> samePhysical = byPhysical.computeIfAbsent(
                        candidate.physicalKey(), ignored -> new ArrayList<>());
                for (ResolvedSource previous : samePhysical) {
                    if (!Arrays.equals(previous.capturedUtf8Bytes(), candidate.capturedUtf8Bytes())) {
                        return Selection.failure(Diagnostic.error(
                                CompilerDiagnosticCodes.RESOLVE_DUPLICATE_PHYSICAL_SOURCE,
                                importSpan,
                                "physical source " + candidate.physicalKey()
                                        + " supplied different bytes for " + logicalModule,
                                List.of(related(previous), related(candidate))));
                    }
                    if (!previous.sourceId().equals(candidate.sourceId())) {
                        return Selection.failure(Diagnostic.error(
                                CompilerDiagnosticCodes.RESOLVE_DUPLICATE_MATCH,
                                importSpan,
                                "physical source " + candidate.physicalKey()
                                        + " has conflicting stable identities for " + logicalModule,
                                List.of(related(previous), related(candidate))));
                    }
                }
                samePhysical.add(candidate);
            }

            if (byPhysical.size() > 1) {
                List<ResolvedSource> distinct = byPhysical.values().stream()
                        .flatMap(List::stream)
                        .sorted(candidateComparator())
                        .toList();
                boolean sameStableIdentity = distinct.stream()
                        .map(ResolvedSource::sourceId)
                        .distinct()
                        .count() == 1;
                return Selection.failure(Diagnostic.error(
                        CompilerDiagnosticCodes.RESOLVE_DUPLICATE_MATCH,
                        importSpan,
                        sameStableIdentity
                                ? "multiple physical sources claim stable identity "
                                        + distinct.getFirst().sourceId()
                                : "multiple sources satisfy logical module " + logicalModule,
                        distinct.stream().map(Discovery::related).toList()));
            }

            return Selection.success(byPhysical.values().iterator().next().getFirst());
        }

        private Materialized materialize(ResolvedSource source) {
            SourceSnapshot snapshot = snapshotsByPhysical.get(source.physicalKey());
            if (snapshot != null
                    && (!snapshot.sourceId().equals(source.sourceId())
                    || !Arrays.equals(snapshot.capturedUtf8Bytes(), source.capturedUtf8Bytes()))) {
                return Materialized.failure(Diagnostic.error(
                        CompilerDiagnosticCodes.RESOLVE_DUPLICATE_PHYSICAL_SOURCE,
                        SourceSpan.at(source.sourceId(), 0),
                        "physical source " + source.physicalKey()
                                + " cannot be reused with a different stable identity or byte sequence"));
            }
            if (snapshot == null) {
                byte[] bytes = source.capturedUtf8Bytes();
                byte[] cachedBytes = bytesByPhysical.get(source.physicalKey());
                if (cachedBytes != null && !Arrays.equals(cachedBytes, bytes)) {
                    return Materialized.failure(Diagnostic.error(
                            CompilerDiagnosticCodes.RESOLVE_DUPLICATE_PHYSICAL_SOURCE,
                            SourceSpan.at(source.sourceId(), 0),
                            "physical source " + source.physicalKey()
                                    + " changed while being discovered"));
                }
                bytesByPhysical.putIfAbsent(source.physicalKey(), bytes.clone());
                PhaseResult<SourceSnapshot> captured = SourceSnapshot.capture(
                        source.sourceId(), source.physicalKey(), bytes);
                if (captured instanceof PhaseResult.Failure<?> failure) {
                    return Materialized.failure(failure.diagnostics().getFirst());
                }
                snapshot = ((PhaseResult.Success<SourceSnapshot>) captured).value();
                snapshotsByPhysical.put(source.physicalKey(), snapshot);
            }

            SyntaxProgram program = programsByPhysical.get(source.physicalKey());
            if (program == null) {
                PhaseResult<SyntaxProgram> parsed = parse(snapshot);
                if (parsed instanceof PhaseResult.Failure<?> failure) {
                    return Materialized.failure(failure.diagnostics().getFirst());
                }
                program = ((PhaseResult.Success<SyntaxProgram>) parsed).value();
            }
            return Materialized.success(snapshot, program);
        }

        private PhaseResult<ModuleGraph> finish() {
            while (!pending.isEmpty()) {
                Draft current = pending.remove();
                List<ImportRequest> imports = new ArrayList<>();
                for (SyntaxNode.ImportDeclaration declaration : current.program.imports()) {
                    try {
                        imports.add(new ImportRequest(
                                LogicalModuleId.fromImportPath(declaration.path()),
                                declaration.path().span()));
                    } catch (IllegalArgumentException exception) {
                        return failure(Diagnostic.error(
                                CompilerDiagnosticCodes.MODULE_INVALID_IMPORT_PATH,
                                declaration.path().span(),
                                "invalid logical import path: " + exception.getMessage()));
                    }
                }
                imports.sort(Comparator.comparing(ImportRequest::logicalModule, LOGICAL_ORDER)
                        .thenComparingInt(request -> request.span().startOffset()));

                for (ImportRequest request : imports) {
                    Selection selection = resolve(request.logicalModule(), request.span());
                    if (selection.diagnostic() != null) {
                        return failure(selection.diagnostic());
                    }
                    Materialized materialized = materialize(selection.source());
                    if (materialized.diagnostic() != null) {
                        return failure(materialized.diagnostic());
                    }
                    ModuleId targetId = ModuleId.fromSourceId(materialized.snapshot().sourceId());
                    if (forbiddenImportedIdentity
                            .filter(identity -> identity.equals(targetId.sourceId()))
                            .isPresent()) {
                        return failure(Diagnostic.error(
                                CompilerDiagnosticCodes.MODULE_DUPLICATE_IDENTITY,
                                request.span(),
                                "session root source identity cannot be imported: "
                                        + targetId));
                    }
                    Registration registration = register(
                            targetId,
                            request.logicalModule(),
                            materialized.snapshot(),
                            materialized.program());
                    if (registration.diagnostic() != null) {
                        return failure(registration.diagnostic());
                    }
                    edges.add(new ModuleGraph.Edge(
                            current.moduleId,
                            request.logicalModule(),
                            targetId,
                            request.span()));
                    if (registration.created()) {
                        pending.add(registration.draft());
                    }
                }
            }

            List<ModuleGraph.Node> nodes = draftsByModule.values().stream()
                    .sorted(DRAFT_ORDER)
                    .map(draft -> new ModuleGraph.Node(
                            draft.moduleId,
                            draft.logicalModule,
                            draft.snapshot,
                            draft.program,
                            draft.logicalModule.filter(LogicalModuleId::isStdIo).isPresent()
                                    ? IntrinsicModule.revision()
                                    : ModuleRevision.compute(
                                            draft.snapshot, configuration.revisionOptions())))
                    .toList();
            return PhaseResult.success(new ModuleGraph(
                    rootModule, nodes, edges, modulesByLogical));
        }

        private Registration register(
                ModuleId moduleId,
                LogicalModuleId logicalModule,
                SourceSnapshot snapshot,
                SyntaxProgram program) {
            Draft logicalExisting = modulesByLogical.get(logicalModule) == null
                    ? null
                    : draftsByModule.get(modulesByLogical.get(logicalModule));
            if (logicalExisting != null && !logicalExisting.moduleId.equals(moduleId)) {
                return Registration.failure(Diagnostic.error(
                        CompilerDiagnosticCodes.MODULE_DUPLICATE_IDENTITY,
                        program.span(),
                        "logical module " + logicalModule + " resolves to more than one stable identity",
                        List.of(related(logicalExisting.snapshot), related(snapshot))));
            }

            Draft physicalExisting = draftsByPhysical.get(snapshot.physicalKey());
            if (physicalExisting != null && !physicalExisting.moduleId.equals(moduleId)) {
                return Registration.failure(Diagnostic.error(
                        CompilerDiagnosticCodes.RESOLVE_DUPLICATE_PHYSICAL_SOURCE,
                        program.span(),
                        "physical source " + snapshot.physicalKey()
                                + " is used by multiple stable module identities",
                        List.of(related(physicalExisting.snapshot), related(snapshot))));
            }

            Draft moduleExisting = draftsByModule.get(moduleId);
            if (moduleExisting != null) {
                if (!moduleExisting.snapshot.physicalKey().equals(snapshot.physicalKey())) {
                    return Registration.failure(Diagnostic.error(
                            CompilerDiagnosticCodes.MODULE_DUPLICATE_IDENTITY,
                            program.span(),
                            "stable module identity " + moduleId
                                    + " refers to more than one physical source",
                            List.of(related(moduleExisting.snapshot), related(snapshot))));
                }
                modulesByLogical.putIfAbsent(logicalModule, moduleId);
                if (moduleExisting.logicalModule.isEmpty()) {
                    moduleExisting.logicalModule = Optional.of(logicalModule);
                }
                return Registration.existing(moduleExisting);
            }

            Draft created = new Draft(
                    moduleId, Optional.of(logicalModule), snapshot, program);
            draftsByModule.put(moduleId, created);
            draftsByPhysical.put(snapshot.physicalKey(), created);
            modulesByLogical.put(logicalModule, moduleId);
            return Registration.created(created);
        }

        private static Comparator<ResolvedSource> candidateComparator() {
            return Comparator.comparing((ResolvedSource source) -> source.sourceId().value())
                    .thenComparing(source -> source.sourceId().isUri() ? 1 : 0)
                    .thenComparing(source -> source.physicalKey().value())
                    .thenComparing((left, right) -> Arrays.compareUnsigned(
                            left.capturedUtf8Bytes(), right.capturedUtf8Bytes()));
        }

        private static RelatedSpan related(ResolvedSource source) {
            return new RelatedSpan(
                    io.mindspice.lyra.compiler.source.SourceSpan.at(source.sourceId(), 0),
                    "candidate " + source.physicalKey());
        }

        private static RelatedSpan related(SourceSnapshot snapshot) {
            return new RelatedSpan(
                    io.mindspice.lyra.compiler.source.SourceSpan.at(snapshot.sourceId(), 0),
                    "source " + snapshot.physicalKey());
        }

        private record CanonicalRoot(Path path) {
        }

        private record CanonicalRoots(List<CanonicalRoot> roots)
                implements io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact {
            private CanonicalRoots {
                roots = List.copyOf(roots);
            }
        }

        private record ImportRequest(
                LogicalModuleId logicalModule,
                io.mindspice.lyra.compiler.source.SourceSpan span) {
        }

        private static final class Draft {
            private final ModuleId moduleId;
            private Optional<LogicalModuleId> logicalModule;
            private final SourceSnapshot snapshot;
            private final SyntaxProgram program;

            private Draft(
                    ModuleId moduleId,
                    Optional<LogicalModuleId> logicalModule,
                    SourceSnapshot snapshot,
                    SyntaxProgram program) {
                this.moduleId = moduleId;
                this.logicalModule = logicalModule;
                this.snapshot = snapshot;
                this.program = program;
            }
        }

        private record Selection(ResolvedSource source, Diagnostic diagnostic) {
            private static Selection success(ResolvedSource source) {
                return new Selection(source, null);
            }

            private static Selection failure(Diagnostic diagnostic) {
                return new Selection(null, diagnostic);
            }
        }

        private record Materialized(
                SourceSnapshot snapshot,
                SyntaxProgram program,
                Diagnostic diagnostic) {
            private static Materialized success(SourceSnapshot snapshot, SyntaxProgram program) {
                return new Materialized(snapshot, program, null);
            }

            private static Materialized failure(Diagnostic diagnostic) {
                return new Materialized(null, null, diagnostic);
            }
        }

        private record Registration(Draft draft, boolean created, Diagnostic diagnostic) {
            private static Registration created(Draft draft) {
                return new Registration(draft, true, null);
            }

            private static Registration existing(Draft draft) {
                return new Registration(draft, false, null);
            }

            private static Registration failure(Diagnostic diagnostic) {
                return new Registration(null, false, diagnostic);
            }
        }
    }
}
