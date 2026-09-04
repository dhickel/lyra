package io.mindspice.lyra.compiler.api;

import io.mindspice.lyra.compiler.ast.SyntaxProgram;
import io.mindspice.lyra.compiler.artifact.ArtifactAssembly;
import io.mindspice.lyra.compiler.backend.jvm.JvmBytecodeArtifact;
import io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes;
import io.mindspice.lyra.compiler.diagnostic.Diagnostic;
import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.diagnostic.PhaseResult;
import io.mindspice.lyra.compiler.grammar.GrammarMatcher;
import io.mindspice.lyra.compiler.grammar.GrammarProgram;
import io.mindspice.lyra.compiler.ir.TypedIr;
import io.mindspice.lyra.compiler.ir.TypedIrBuilder;
import io.mindspice.lyra.compiler.lex.LexedSource;
import io.mindspice.lyra.compiler.lex.Lexer;
import io.mindspice.lyra.compiler.parse.Parser;
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
import io.mindspice.lyra.runtime.ArtifactMetadata;
import io.mindspice.lyra.runtime.PackagingMode;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
