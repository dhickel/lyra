package io.mindspice.lyra.compiler.backend.jvm;

import io.mindspice.lyra.compiler.artifact.ArtifactAssembly;

import io.mindspice.lyra.compiler.ast.SyntaxProgram;
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
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.ModuleRevision;
import io.mindspice.lyra.compiler.source.PhysicalSourceKey;
import io.mindspice.lyra.compiler.source.SourceSnapshot;
import io.mindspice.lyra.runtime.ArtifactMetadataReader;
import io.mindspice.lyra.runtime.DebugMapReader;
import io.mindspice.lyra.runtime.PackagingMode;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class Phase18SmokeTest {
    @Test
    void assemblyPublishesScalarAndFunctionMetadataAndSources() throws Exception {
        String source = "let @pub answer :I32 = 42\n"
                + "let @pub add :Fn<I32,I32;I32> = (=> |left right| (+ left right))\n";
        ModuleId module = ModuleId.path("app/main.lyra");
        TypedIr ir = lower(module, source);
        JvmBytecodeArtifact bytecode = JvmBytecodeEmitter.emit(ir,
                GeneratedTypePlanner.plan(ir, "app.generated"));

        ArtifactAssembly assembly = ArtifactAssembly.assemble(bytecode,
                PackagingMode.CLASSES, true);
        assertEquals(PackagingMode.CLASSES, assembly.packagingMode());
        assertEquals("app.generated", assembly.metadata().javaPackage());
        assertEquals(2, assembly.metadata().exports().size());
        assertTrue(assembly.metadata().exports().stream()
                .anyMatch(export -> export.name().equals("answer")
                        && export.canonicalContract().equals("I32")));
        assertTrue(assembly.metadata().sources().getFirst().includesSource());
        assertEquals(assembly.debugMap().sha256(), assembly.metadata().debugMapHash());
        assertEquals(assembly.metadata(), ArtifactMetadataReader.read(assembly.artifactJson()));
        assertEquals(assembly.debugMap(), DebugMapReader.read(assembly.debugMapJson(), assembly.metadata()));
        assertTrue(assembly.entryNames().stream().anyMatch(name -> name.endsWith("main.lyra")));
        assertArrayEquals(assembly.artifactJson(), assembly.entry("META-INF/lyra/artifact.json").orElseThrow());

        Path directory = Files.createTempDirectory("lyra-phase18-classes-").resolve("out");
        try {
            assembly.writeClasses(directory, false);
            assertTrue(Files.exists(directory.resolve("META-INF/lyra/artifact.json")));
            Path marker = directory.resolve("keep.txt");
            Files.writeString(marker, "keep", StandardCharsets.UTF_8);
            assertThrows(IllegalArgumentException.class, () -> assembly.writeClasses(directory, false));
            assertEquals("keep", Files.readString(marker, StandardCharsets.UTF_8));
            assembly.writeClasses(directory, true);
            assertFalse(Files.exists(directory.resolve(".lyra-staging")));
            try (var siblings = Files.list(directory.getParent())) {
                assertEquals(0, siblings.filter(path ->
                        path.getFileName().toString().startsWith("out.lyra-")).count());
            }
        } finally {
            try (var paths = Files.walk(directory.getParent())) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @Test
    void publicationRejectsSymbolicLinkAncestors() throws Exception {
        ModuleId module = ModuleId.path("safe/main.lyra");
        TypedIr ir = lower(module, "let @pub answer :I32 = 42");
        JvmBytecodeArtifact bytecode = JvmBytecodeEmitter.emit(ir,
                GeneratedTypePlanner.plan(ir, "safe.generated"));
        ArtifactAssembly assembly = ArtifactAssembly.assemble(bytecode, PackagingMode.CLASSES, false);
        Path root = Files.createTempDirectory("lyra-phase18-link-");
        Path real = root.resolve("real");
        Path link = root.resolve("link");
        Files.createDirectory(real);
        try {
            try {
                Files.createSymbolicLink(link, real);
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException exception) {
                return;
            }
            assertThrows(IllegalArgumentException.class,
                    () -> assembly.writeClasses(link.resolve("out"), false));
            assertFalse(Files.exists(real.resolve("out")));
        } finally {
            Files.deleteIfExists(link);
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @Test
    void pathIdentityWithColonRemainsAPathAcrossRuntimeMetadata() {
        ModuleId module = ModuleId.path("path:main.lyra");
        TypedIr ir = lower(module, "let @pub answer :I32 = 42");
        JvmBytecodeArtifact bytecode = JvmBytecodeEmitter.emit(ir,
                GeneratedTypePlanner.plan(ir, "colon.generated"));
        ArtifactAssembly assembly = ArtifactAssembly.assemble(bytecode,
                PackagingMode.CLASSES, true);

        assertEquals("path:path:main.lyra", assembly.metadata().rootModuleId().canonicalSpelling());
        assertEquals(assembly.metadata(), ArtifactMetadataReader.read(assembly.artifactJson()));
        assertEquals(assembly.debugMap(), DebugMapReader.read(assembly.debugMapJson(), assembly.metadata()));
    }

    @Test
    void assemblyRejectsGeneratedClassesInTheSharedRuntimeNamespace() {
        ModuleId module = ModuleId.path("collision/main.lyra");
        TypedIr ir = lower(module, "let @pub answer :I32 = 42");
        JvmBytecodeArtifact bytecode = JvmBytecodeEmitter.emit(ir,
                GeneratedTypePlanner.plan(ir, "io.mindspice.lyra.runtime.generated"));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ArtifactAssembly.assemble(bytecode, PackagingMode.CLASSES, false));
        assertTrue(failure.getMessage().contains("runtime namespace"));
    }

    @Test
    void bundledJarRejectsTheMissingLaterPhaseLauncher() {
        ModuleId module = ModuleId.path("bundle/main.lyra");
        TypedIr ir = lower(module, "let @pub answer :I32 = 42");
        JvmBytecodeArtifact bytecode = JvmBytecodeEmitter.emit(ir,
                GeneratedTypePlanner.plan(ir, "bundle.generated"));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ArtifactAssembly.assemble(bytecode, PackagingMode.BUNDLED_JAR, false));
        assertTrue(failure.getMessage().contains("LyraLauncher"));
    }

    @Test
    void nullableFunctionExportsRetainTheirCompleteMetadataContract() {
        ModuleId module = ModuleId.path("nullable-function/main.lyra");
        TypedIr ir = lower(module, "let @pub @nil computed :Fn<;I32> = #NIL");
        JvmBytecodeArtifact bytecode = JvmBytecodeEmitter.emit(ir,
                GeneratedTypePlanner.plan(ir, "nullable.generated"));

        ArtifactAssembly assembly = ArtifactAssembly.assemble(bytecode,
                PackagingMode.CLASSES, false);
        assertEquals("@nilFn<;I32>", assembly.metadata().exports().getFirst().canonicalContract());
        assertEquals(assembly.metadata(), ArtifactMetadataReader.read(assembly.artifactJson()));
    }

    @Test
    void thinJarIsStoredReproducibleAndUsesEpochEntries() throws Exception {
        ModuleId module = ModuleId.path("jar/main.lyra");
        TypedIr ir = lower(module, "let @pub answer :I32 = 42");
        JvmBytecodeArtifact bytecode = JvmBytecodeEmitter.emit(ir,
                GeneratedTypePlanner.plan(ir, "jar.generated"));
        ArtifactAssembly assembly = ArtifactAssembly.assemble(bytecode, PackagingMode.THIN_JAR, false);
        Path first = Files.createTempFile("lyra-phase18-first-", ".jar");
        Path second = Files.createTempFile("lyra-phase18-second-", ".jar");
        Files.delete(first);
        Files.delete(second);
        try {
            assembly.writeJar(first, false);
            assembly.writeJar(second, false);
            assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));
            assertTrue(assembly.metadata().runtimeRequirement().isPresent());
            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(first.toFile())) {
                var entry = jar.entries().nextElement();
                assertEquals("META-INF/MANIFEST.MF", entry.getName());
                assertEquals(java.util.zip.ZipEntry.STORED, entry.getMethod());
                List<String> names = jar.stream().map(java.util.zip.ZipEntry::getName).toList();
                assertEquals(names.stream().sorted().toList(), names);
                String manifest = new String(jar.getInputStream(entry).readAllBytes(),
                        StandardCharsets.UTF_8);
                assertTrue(manifest.startsWith("Manifest-Version: 1.0\r\n"));
                assertTrue(manifest.endsWith("\r\n\r\n"));
                byte[] jarBytes = Files.readAllBytes(first);
                assertEquals(0, jarBytes[10]);
                assertEquals(0, jarBytes[11]);
                assertEquals(0x21, jarBytes[12]);
                assertEquals(0, jarBytes[13]);
                assertEquals(null, entry.getExtra());
                assertEquals(null, entry.getComment());
            }
        } finally {
            Files.deleteIfExists(first);
            Files.deleteIfExists(second);
        }
    }

    private static TypedIr lower(ModuleId id, String source) {
        SourceSnapshot snapshot = success(SourceSnapshot.capture(id.sourceId(),
                PhysicalSourceKey.uri(URI.create("memory:" + id.value())),
                source.getBytes(StandardCharsets.UTF_8)));
        LexedSource lexed = success(Lexer.lex(snapshot));
        GrammarProgram grammar = success(GrammarMatcher.match(lexed));
        SyntaxProgram syntax = success(Parser.parse(lexed, grammar));
        ModuleGraph.Node node = new ModuleGraph.Node(id,
                Optional.empty(), snapshot, syntax, ModuleRevision.compute(snapshot));
        ModuleGraph graph = new ModuleGraph(id, List.of(node), List.of(), Map.of());
        ResolvedSemanticGraph resolved = success(SemanticResolver.resolve(graph));
        TypedSemanticGraph typed = success(TypeChecker.check(resolved));
        return success(TypedIrBuilder.lower(typed));
    }

    private static <T extends ImmutablePhaseArtifact> T success(PhaseResult<T> result) {
        if (result instanceof PhaseResult.Success<T> success) {
            return success.value();
        }
        throw new AssertionError(result.diagnostics());
    }
}
