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
import io.mindspice.lyra.compiler.ir.TypedIr;
import io.mindspice.lyra.compiler.ir.TypedIrBuilder;
import io.mindspice.lyra.compiler.lex.LexedSource;
import io.mindspice.lyra.compiler.lex.Lexer;
import io.mindspice.lyra.compiler.parse.Parser;
import io.mindspice.lyra.compiler.semantic.DeclarationKind;
import io.mindspice.lyra.compiler.semantic.ResolvedDeclaration;
import io.mindspice.lyra.compiler.semantic.ResolvedExport;
import io.mindspice.lyra.compiler.semantic.ResolvedSemanticGraph;
import io.mindspice.lyra.compiler.semantic.SemanticResolver;
import io.mindspice.lyra.compiler.semantic.TypeChecker;
import io.mindspice.lyra.compiler.semantic.TypedSemanticGraph;
import io.mindspice.lyra.compiler.source.ModuleGraph;
import io.mindspice.lyra.compiler.source.ModuleGraphDiscovery;
import io.mindspice.lyra.compiler.source.PhysicalSourceKey;
import io.mindspice.lyra.compiler.source.SourceConfiguration;
import io.mindspice.lyra.compiler.source.SourceId;
import io.mindspice.lyra.compiler.source.SourceSnapshot;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.session.ExternalBinding;
import io.mindspice.lyra.compiler.session.SessionSnapshot;
import io.mindspice.lyra.compiler.session.StorageIdentity;
import io.mindspice.lyra.runtime.ArtifactMetadata;
import io.mindspice.lyra.runtime.PackagingMode;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
     * imported multi-module session linkage is still rejected structurally rather
     * than replaced with fabricated initializer code.</p>
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

        private SessionPipeline(SessionCompileRequest request) {
            this.request = request;
        }

        private SessionCompileResult run() {
            Diagnostic configuration = validateConfiguration();
            if (configuration != null) {
                return fail(configuration);
            }
            if (!request.snapshot().pinnedModules().isEmpty()) {
                return fail(Diagnostic.error(
                        CompilerDiagnosticCodes.SESSION_EXTERNAL_BINDING_UNSUPPORTED,
                        sourceSpan(),
                        "pinned session modules cannot be linked into this submission yet"));
            }

            ModuleGraph graph = phase(discover());
            if (graph == null) {
                return failure();
            }

            ResolvedSemanticGraph resolved = phase(SemanticResolver.resolveSession(graph, request.snapshot()));
            if (resolved == null) {
                return failure();
            }

            TypedSemanticGraph typed = phase(TypeChecker.check(resolved));
            if (typed == null) {
                return failure();
            }

            TypedIr ir = phase(TypedIrBuilder.lowerSubmission(typed));
            if (ir == null) {
                return failure();
            }

            JvmBytecodeArtifact bytecode = phase(JvmBytecodeArtifact.emit(
                    ir, request.javaBasePackage()));
            if (bytecode == null) {
                return failure();
            }

            StagedNamespace staged = stageNamespace(graph, resolved, typed);
            if (staged.diagnostic() != null) {
                return fail(staged.diagnostic());
            }
            SessionFlowCertificate stagedCertificate = SessionFlowCertificate.issue(
                    request.snapshot(), typed, staged.snapshot().bindings(), false);
            SessionSnapshot stagedSnapshot = staged.snapshot()
                    .withFlowCertificate(stagedCertificate);
            SessionFlowCertificate attemptedCertificate = SessionFlowCertificate.issue(
                    request.snapshot(), typed, request.snapshot().bindings(), true);
            SessionSnapshot attemptedSnapshot = request.snapshot()
                    .withAllocator(typed.allocator())
                    .withFlowCertificate(attemptedCertificate);
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
                    List.of(),
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
            return ModuleGraphDiscovery.discoverSession(
                    syntax, snapshot, request.sourceConfiguration());
        }

        private StagedNamespace stageNamespace(
                ModuleGraph graph, ResolvedSemanticGraph resolved, TypedSemanticGraph typed) {
            var root = graph.rootModule();
            var rootSemantic = resolved.module(root).orElseThrow();
            for (ResolvedDeclaration declaration : resolved.declarations()) {
                if (!declaration.moduleId().equals(root)
                        || !declaration.scopeId().equals(rootSemantic.rootScope())
                        || declaration.kind() == DeclarationKind.EXTERNAL) {
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

            List<DeclarationId> declarations = resolved.declarations().stream()
                    .filter(value -> value.moduleId().equals(root))
                    .filter(value -> value.scopeId().equals(rootSemantic.rootScope()))
                    .filter(value -> value.kind() == DeclarationKind.LET)
                    .map(ResolvedDeclaration::id)
                    .toList();
            try {
                return StagedNamespace.success(request.snapshot().nextRevision(
                        nextBindings,
                        request.snapshot().imports(),
                        request.snapshot().pinnedModules(),
                        typed.allocator()), declarations);
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
            return null;
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
            return LyraCompiler.mapSessionDiagnostics(
                    values, request.source(), request.sourceId());
        }

        private record StagedNamespace(
                SessionSnapshot snapshot,
                List<DeclarationId> declarations,
                Diagnostic diagnostic) {
            private StagedNamespace {
                if (snapshot == null && diagnostic == null) {
                    throw new IllegalArgumentException(
                            "staged namespace needs a snapshot or diagnostic");
                }
                if (snapshot != null && diagnostic != null) {
                    throw new IllegalArgumentException(
                            "staged namespace cannot contain both a snapshot and diagnostic");
                }
                declarations = List.copyOf(Objects.requireNonNull(declarations, "declarations"));
            }

            private static StagedNamespace success(
                    SessionSnapshot snapshot, List<DeclarationId> declarations) {
                return new StagedNamespace(
                        Objects.requireNonNull(snapshot, "snapshot"), declarations, null);
            }

            private static StagedNamespace failure(Diagnostic diagnostic) {
                return new StagedNamespace(null, List.of(),
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
