package io.mindspice.lyra.compiler.api;

import io.mindspice.lyra.compiler.source.ResolvedSource;
import io.mindspice.lyra.compiler.source.SourceSnapshot;
import io.mindspice.lyra.compiler.artifact.ArtifactAssemblyException;
import io.mindspice.lyra.runtime.ArtifactDependency;
import io.mindspice.lyra.runtime.ArtifactHook;
import io.mindspice.lyra.runtime.ArtifactMetadata;
import io.mindspice.lyra.runtime.ArtifactMetadataReader;
import io.mindspice.lyra.runtime.ArtifactProfile;
import io.mindspice.lyra.runtime.ArtifactRevision;
import io.mindspice.lyra.runtime.LyraCompatibilityException;
import io.mindspice.lyra.runtime.LyraRuntime;
import io.mindspice.lyra.runtime.LyraRuntimeConstants;
import io.mindspice.lyra.runtime.ModuleId;
import io.mindspice.lyra.runtime.ModuleRevision;
import io.mindspice.lyra.runtime.SourceMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Debug-capability packaging and packaged source reconstruction contract.
 * These tests deliberately cross the public compiler, artifact assembly,
 * canonical metadata, and embedded-source boundaries.
 */
final class ReplPackagingCompatibilityTest {
    private static final String ROOT_SOURCE = "import dep "
            + "let @pub main :Fn<Array<String>;I32> = (=> |args| dep->:.value)\n";
    private static final String DEP_SOURCE = "let @pub value :I32 = 41\n";

    @TempDir
    Path temp;

    @Test
    void debugCapableNormalEmbedsTheCompleteContextAndRoundTrips() throws Exception {
        Map<String, String> options = Map.of("checked-arithmetic", "on", "debug-level", "source");
        CompiledArtifact artifact = compile(CompileRequest.builder()
                .source("main.lyra", ROOT_SOURCE)
                .resolver(SourceResolver.memory(ResolvedSource.memory("dep",
                        URI.create("memory:dep.lyra"), DEP_SOURCE)))
                .semanticOptions(options)
                .debugCapable(true)
                .build());
        ArtifactMetadata metadata = artifact.metadata();
        assertTrue(metadata.replCapable());
        assertEquals(1, metadata.replCapability().orElseThrow().schema());
        assertEquals(ArtifactProfile.NORMAL, metadata.artifactProfile());
        assertTrue(metadata.hookRequirements().isEmpty());
        assertFalse(metadata.attachmentContext().isPresent());
        // Every reachable source snapshot is embedded and hash-consistent,
        // including resolver-produced URI sources.
        assertEquals(2, metadata.sources().size());
        for (SourceMetadata source : metadata.sources()) {
            String entry = source.entryName().orElseThrow();
            byte[] bytes = artifact.entry(entry).orElseThrow();
            assertEquals(source.sha256(), sha256Hex(bytes));
        }
        // Canonical import resolution topology with exact source spans.
        assertEquals(1, metadata.imports().size());
        assertEquals(ModuleId.path("main.lyra"), metadata.imports().getFirst().fromModule());
        assertEquals("dep", metadata.imports().getFirst().logicalTarget());
        assertTrue(metadata.imports().getFirst().endOffset() > metadata.imports().getFirst().startOffset());
        // Reproducible scalar options are the original request options.
        assertEquals(options, metadata.reproducibleOptions());
        // The exact fixed closure requirement.
        assertEquals(List.of(
                new ArtifactDependency("io.mindspice", "lyra-compiler",
                        LyraRuntimeConstants.COMPILER_VERSION, ArtifactProfile.NORMAL),
                new ArtifactDependency("io.mindspice", "lyra-repl",
                        LyraRuntimeConstants.REPL_VERSION, ArtifactProfile.NORMAL),
                new ArtifactDependency("io.mindspice", "lyra-runtime",
                        LyraRuntimeConstants.RUNTIME_VERSION, ArtifactProfile.NORMAL)),
                metadata.dependencyRequirements());
        // Canonical round-trip and facade publication carry the capability.
        assertEquals(metadata, ArtifactMetadataReader.read(metadata.canonicalUtf8()));
        assertTrue(metadata.canonicalJson().contains("\"replCapability\":{\"schema\":1}"));
        String facade = artifact.classes().keySet().stream()
                .filter(name -> name.contains(".$lyra$facade$")).findFirst().orElseThrow();
        String facadeEntry = facade.replace('.', '/') + ".class";
        assertTrue(contains(artifact.entry(facadeEntry).orElseThrow(), "replCapability"));
        // Debug capability never changes the generated-code ABI.
        assertFalse(artifact.classes().values().stream()
                .anyMatch(bytes -> contains(bytes, "$lyra$attachment")));
    }

    @Test
    void attachableDebugDeclaresHooksAndClosureRequirements() {
        CompiledArtifact artifact = compile(CompileRequest.builder()
                .source("main.lyra", ROOT_SOURCE)
                .resolver(SourceResolver.memory(ResolvedSource.memory("dep",
                        URI.create("memory:dep.lyra"), DEP_SOURCE)))
                .profile(CompileProfile.ATTACHABLE)
                .debugCapable(true)
                .build());
        ArtifactMetadata metadata = artifact.metadata();
        assertTrue(metadata.replCapable());
        assertEquals(ArtifactProfile.ATTACHABLE, metadata.artifactProfile());
        assertEquals(List.of(new ArtifactHook("$lyra$attachmentLifecycle",
                        "()Lio/mindspice/lyra/runtime/ModuleLifecycle;"),
                new ArtifactHook("$lyra$attachmentSafePoint", "()V")),
                metadata.hookRequirements());
        assertTrue(metadata.attachmentContext().isPresent());
        assertEquals(List.of(
                new ArtifactDependency("io.mindspice", "lyra-compiler",
                        LyraRuntimeConstants.COMPILER_VERSION, ArtifactProfile.NORMAL),
                new ArtifactDependency("io.mindspice", "lyra-repl",
                        LyraRuntimeConstants.REPL_VERSION, ArtifactProfile.NORMAL),
                new ArtifactDependency("io.mindspice", "lyra-runtime",
                        LyraRuntimeConstants.RUNTIME_VERSION, ArtifactProfile.ATTACHABLE)),
                metadata.dependencyRequirements());
        assertEquals(metadata, ArtifactMetadataReader.read(metadata.canonicalUtf8()));
    }

    @Test
    void debugClassesAndThinJarsAreByteIdenticalAcrossRepeatBuilds() throws Exception {
        CompiledArtifact artifact = compile(CompileRequest.builder()
                .source("main.lyra", ROOT_SOURCE)
                .resolver(SourceResolver.memory(ResolvedSource.memory("dep",
                        URI.create("memory:dep.lyra"), DEP_SOURCE)))
                .debugCapable(true)
                .build());
        Path firstClasses = temp.resolve("first-classes");
        Path secondClasses = temp.resolve("second-classes");
        artifact.writeClasses(firstClasses);
        artifact.writeClasses(secondClasses);
        assertSameEntries(entryMap(firstClasses), entryMap(secondClasses));
        Path firstThin = temp.resolve("first-thin.jar");
        Path secondThin = temp.resolve("second-thin.jar");
        artifact.writeJar(firstThin, JarMode.THIN_JAR);
        artifact.writeJar(secondThin, JarMode.THIN_JAR);
        assertArrayEquals(Files.readAllBytes(firstThin), Files.readAllBytes(secondThin));
        // Deterministic publication: stored, sorted, epoch, no directory entries.
        try (JarFile jar = new JarFile(firstThin.toFile())) {
            List<String> names = jar.stream()
                    .map(java.util.zip.ZipEntry::getName).toList();
            assertTrue(jar.stream().noneMatch(entry -> entry.isDirectory()));
            assertTrue(jar.stream().allMatch(entry ->
                    entry.getMethod() == java.util.zip.ZipEntry.STORED));
            assertEquals(names.stream().sorted().toList(), names);
        }
    }

    @Test
    void pathAndUriSourceEntriesRemainDistinctWhenTheirHistoricalNamesCollide() {
        CompiledArtifact seed = compile(CompileRequest.builder()
                .source("main.lyra", ROOT_SOURCE)
                .resolver(SourceResolver.memory(ResolvedSource.memory("dep",
                        URI.create("memory:dep.lyra"), DEP_SOURCE)))
                .debugCapable(true)
                .build());
        String uriEntry = seed.metadata().sources().stream()
                .filter(source -> source.sourceId().isUri())
                .findFirst().orElseThrow().entryName().orElseThrow();
        String collidingPath = uriEntry.substring("META-INF/lyra/sources/".length());
        CompiledArtifact artifact = compile(CompileRequest.builder()
                .source(collidingPath, ROOT_SOURCE)
                .resolver(SourceResolver.memory(ResolvedSource.memory("dep",
                        URI.create("memory:dep.lyra"), DEP_SOURCE)))
                .debugCapable(true)
                .build());
        List<String> entries = artifact.metadata().sources().stream()
                .map(source -> source.entryName().orElseThrow()).toList();
        assertEquals(entries.size(), entries.stream().distinct().count());
        for (String entry : entries) {
            assertTrue(artifact.entry(entry).isPresent(), entry);
        }
    }

    @Test
    void debugGeneratedNamespaceCannotOverlapItsBundledClosure() {
        ArtifactAssemblyException failure = assertThrows(ArtifactAssemblyException.class,
                () -> compile(CompileRequest.builder()
                        .source("main.lyra", "let @pub main :Fn<Array<String>;I32> = (=> |args| 7)\n")
                        .javaBasePackage("io.mindspice.lyra.compiler.generated")
                        .debugCapable(true)
                        .build()));
        assertTrue(failure.getMessage().contains("production closure"), failure.getMessage());
    }

    @Test
    void sessionProfileRejectsDebugCapabilityAtRequestConstruction() {
        assertThrows(IllegalArgumentException.class, () -> CompileRequest.builder()
                .source("main.lyra", ROOT_SOURCE)
                .profile(CompileProfile.SESSION)
                .debugCapable(true)
                .build());
    }

    @Test
    void debugBundledPackagingWithoutReplClosureIsAnActionableError() {
        CompiledArtifact artifact = compile(CompileRequest.builder()
                .source("main.lyra", ROOT_SOURCE)
                .resolver(SourceResolver.memory(ResolvedSource.memory("dep",
                        URI.create("memory:dep.lyra"), DEP_SOURCE)))
                .debugCapable(true)
                .build());
        // The compiler-only test classpath carries no lyra-repl distribution;
        // full debug bundling executes in the CLI distribution.
        io.mindspice.lyra.compiler.artifact.ArtifactAssemblyException failure =
                assertThrows(io.mindspice.lyra.compiler.artifact.ArtifactAssemblyException.class,
                        () -> artifact.writeJar(temp.resolve("debug.jar"), JarMode.BUNDLED_JAR));
        assertTrue(failure.getMessage().contains("lyra-repl production closure"), failure.getMessage());
    }

    @Test
    void reconstructionRebuildsTheIdenticalGraphAfterEditDeletionAndResolverLoss() throws Exception {
        // Path-based graph with real files that are later edited and deleted.
        Path sourceRoot = temp.resolve("project");
        Files.createDirectories(sourceRoot);
        Path mainFile = sourceRoot.resolve("main.lyra");
        Path depFile = sourceRoot.resolve("dep.lyra");
        Files.writeString(mainFile, ROOT_SOURCE);
        Files.writeString(depFile, DEP_SOURCE);
        CompiledArtifact artifact = compile(CompileRequest.builder()
                .root(mainFile)
                .sourceRoot(sourceRoot)
                .semanticOptions(Map.of("checked-arithmetic", "on"))
                .debugCapable(true)
                .build());
        DebugArtifactContext context = DebugArtifactContext.read(artifact);
        assertEquals(2, context.sources().size());
        // Editing and deleting the originals must not matter: reconstruction
        // uses only the embedded snapshots, never the files.
        Files.delete(depFile);
        Files.writeString(mainFile, "let @pub main :Fn<Array<String>;I32> = (=> |args| 99)\n");
        CompileResult rebuilt = context.rebuild();
        CompileResult.Success success = assertInstanceOf(CompileResult.Success.class, rebuilt);
        assertEquals(artifact.metadata().modules(), success.artifact().metadata().modules());
        assertEquals(artifact.metadata().rootModuleRevision(),
                success.artifact().metadata().rootModuleRevision());
        assertEquals(artifact.metadata().imports(), success.artifact().metadata().imports());
        assertEquals(artifact.metadata().reproducibleOptions(),
                success.artifact().metadata().reproducibleOptions());

        // Resolver-produced URI sources survive losing the resolver object.
        var resolver = SourceResolver.memory(ResolvedSource.memory("dep",
                URI.create("memory:dep.lyra"), DEP_SOURCE));
        CompiledArtifact resolverArtifact = compile(CompileRequest.builder()
                .source("memory:main.lyra", ROOT_SOURCE)
                .resolver(resolver)
                .semanticOptions(Map.of("checked-arithmetic", "on"))
                .profile(CompileProfile.ATTACHABLE)
                .debugCapable(true)
                .build());
        // The original resolver is now unreachable; only the embedded
        // snapshots remain.
        resolver = null;
        DebugArtifactContext resolverContext = DebugArtifactContext.read(resolverArtifact);
        assertTrue(resolverContext.sources().stream()
                .anyMatch(snapshot -> snapshot.sourceId().isUri()));
        CompileResult resolverRebuilt = resolverContext.rebuild();
        CompileResult.Success resolverSuccess =
                assertInstanceOf(CompileResult.Success.class, resolverRebuilt);
        assertEquals(resolverArtifact.metadata().modules(),
                resolverSuccess.artifact().metadata().modules());
        assertEquals(resolverArtifact.metadata().attachmentContext().orElseThrow().graphRevision(),
                resolverSuccess.artifact().metadata().attachmentContext().orElseThrow().graphRevision());
        assertEquals(resolverArtifact.metadata().attachmentContext().orElseThrow().sourceInventoryRevision(),
                resolverSuccess.artifact().metadata().attachmentContext().orElseThrow().sourceInventoryRevision());
    }

    @Test
    void reconstructionExecutesNoInitializersAndPreservesUtf16Fidelity() {
        String unicodeDep = "import std->io->{println} (println \"INIT-EFFECT\") "
                + "let @pub value :String = \"\uD83D\uDE00-\u00E9\"\n";
        CompiledArtifact artifact = compile(CompileRequest.builder()
                .source("main.lyra", "import dep let @pub main :Fn<Array<String>;I32> = "
                        + "(=> |args| 7)")
                .resolver(SourceResolver.memory(ResolvedSource.memory("dep",
                        URI.create("memory:dep.lyra"), unicodeDep)))
                .debugCapable(true)
                .build());
        DebugArtifactContext context = DebugArtifactContext.read(artifact);
        String originalOut = captureOut(() -> context.rebuild());
        assertEquals("", originalOut, "compilation must never execute module initializers");
        String text = context.sources().stream()
                .filter(snapshot -> snapshot.sourceId().value().equals("memory:dep.lyra"))
                .findFirst().orElseThrow().text();
        assertTrue(text.contains("\uD83D\uDE00-\u00E9"));
        assertTrue(text.contains("INIT-EFFECT"));
    }

    @Test
    void reconstructionRejectsNonDebugMalformedAndTamperedContexts() throws Exception {
        CompiledArtifact normal = compile(CompileRequest.builder()
                .source("main.lyra", ROOT_SOURCE)
                .resolver(SourceResolver.memory(ResolvedSource.memory("dep",
                        URI.create("memory:dep.lyra"), DEP_SOURCE)))
                .build());
        assertThrows(LyraCompatibilityException.class,
                () -> DebugArtifactContext.read(normal));

        CompiledArtifact debug = compile(CompileRequest.builder()
                .source("main.lyra", ROOT_SOURCE)
                .resolver(SourceResolver.memory(ResolvedSource.memory("dep",
                        URI.create("memory:dep.lyra"), DEP_SOURCE)))
                .debugCapable(true)
                .build());
        // A tampered embedded source breaks the hash contract.
        LinkedHashMap<String, byte[]> tamperedBytes = new LinkedHashMap<>(debug.entries());
        String sourceEntry = debug.metadata().sources().getFirst().entryName().orElseThrow();
        tamperedBytes.put(sourceEntry, "let @pub value :I32 = 99".getBytes(StandardCharsets.UTF_8));
        assertThrows(LyraCompatibilityException.class, () -> DebugArtifactContext.read(
                io.mindspice.lyra.runtime.ArtifactSource.fromEntries(
                        debug.metadata(), tamperedBytes)));
        // A missing embedded source entry is a structured error.
        LinkedHashMap<String, byte[]> missingEntry = new LinkedHashMap<>(debug.entries());
        missingEntry.remove(sourceEntry);
        assertThrows(LyraCompatibilityException.class, () -> DebugArtifactContext.read(
                io.mindspice.lyra.runtime.ArtifactSource.fromEntries(
                        debug.metadata(), missingEntry)));
        // Metadata without the capability declaration is rejected by the
        // canonical reader itself.
        String strippedJson = debug.metadata().canonicalJson().replace(
                ",\"replCapability\":{\"schema\":1}", "");
        assertThrows(LyraCompatibilityException.class,
                () -> ArtifactMetadataReader.read(strippedJson));
    }

    @Test
    void facadeMetadataChunksSurviveJvmConstantPoolLimits() {
        LinkedHashMap<String, String> options = new LinkedHashMap<>();
        for (int index = 0; index < 5_000; index++) {
            options.put("semantic-option-" + index, "value-" + index);
        }
        CompiledArtifact artifact = compile(CompileRequest.builder()
                .source("main.lyra", "let @pub main :Fn<Array<String>;I32> = (=> |args| 7)\n")
                .semanticOptions(options)
                .debugCapable(true)
                .build());
        assertEquals(options, artifact.metadata().reproducibleOptions());
        assertTrue(artifact.classes().values().stream().anyMatch(bytes -> {
            String text = new String(bytes, StandardCharsets.ISO_8859_1);
            return text.contains("semantic-option-4999")
                    && !text.contains("_lyraProvisional");
        }));
        assertEquals(artifact.metadata(), ArtifactMetadataReader.read(
                artifact.metadata().canonicalUtf8()));
        try (var loaded = LyraRuntime.load(artifact)) {
            assertEquals(artifact.metadata(), loaded.metadata());
        }
    }

    @Test
    void noActivationOrTimingValuesEnterArtifactBytes() {
        CompiledArtifact artifact = compile(CompileRequest.builder()
                .source("main.lyra", ROOT_SOURCE)
                .resolver(SourceResolver.memory(ResolvedSource.memory("dep",
                        URI.create("memory:dep.lyra"), DEP_SOURCE)))
                .semanticOptions(Map.of("checked-arithmetic", "on"))
                .debugCapable(true)
                .build());
        String json = artifact.metadata().canonicalJson();
        for (String forbidden : List.of("port", "endpoint", "wait", "listen",
                "timestamp", "clock", "host")) {
            assertFalse(json.contains("\"" + forbidden), forbidden + " entered metadata");
        }
        assertEquals(Map.of("checked-arithmetic", "on"),
                artifact.metadata().reproducibleOptions());
    }

    @Test
    void normalArtifactsRemainFreeOfDebugContextAndProductionClosure() throws Exception {
        CompiledArtifact artifact = compile(CompileRequest.builder()
                .source("main.lyra", "let @pub main :Fn<Array<String>;I32> = (=> |args| 7)\n")
                .build());
        ArtifactMetadata metadata = artifact.metadata();
        assertFalse(metadata.replCapable());
        assertFalse(metadata.canonicalJson().contains("replCapability"));
        assertTrue(metadata.dependencyRequirements().isEmpty());
        assertTrue(metadata.imports().isEmpty());
        assertTrue(metadata.reproducibleOptions().isEmpty());
        Path bundled = temp.resolve("normal.jar");
        artifact.writeJar(bundled, JarMode.BUNDLED_JAR);
        try (JarFile jar = new JarFile(bundled.toFile())) {
            assertEquals("io.mindspice.lyra.runtime.LyraLauncher",
                    jar.getManifest().getMainAttributes().getValue("Main-Class"));
            assertTrue(jar.stream().anyMatch(entry ->
                    entry.getName().equals("io/mindspice/lyra/runtime/LyraLauncher.class")));
            assertTrue(jar.stream().noneMatch(entry ->
                    entry.getName().startsWith("io/mindspice/lyra/compiler/")
                            || entry.getName().startsWith("io/mindspice/lyra/repl/")
                            || entry.getName().contains("jline")
                            || entry.getName().startsWith("io/mindspice/lyra/cli/")));
        }
    }

    @Test
    void legacySchema1FixtureLoadsUnchangedThroughTheCompilerBoundary() throws Exception {
        byte[] legacy = getClass().getClassLoader().getResourceAsStream(
                "legacy-artifact-v1-normal.json").readAllBytes();
        ArtifactMetadata metadata = ArtifactMetadataReader.read(legacy);
        assertFalse(metadata.replCapable());
        assertEquals(ArtifactProfile.NORMAL, metadata.artifactProfile());
        // The revision remains reproducible with the capability input absent.
        assertEquals(metadata.artifactRevision(), ArtifactRevision.compute(
                metadata.compilerBuild(), metadata.modules(), metadata.javaNameMap(),
                metadata.profile(), metadata.packagingMode(), metadata.previewRequired(),
                metadata.javaPackage(), metadata.sources(), metadata.runtimeRequirement(),
                metadata.executionProfile(), metadata.hookRequirements(),
                metadata.dependencyRequirements(), metadata.attachmentContext(),
                metadata.imports(), metadata.reproducibleOptions(), false));
    }

    private static CompiledArtifact compile(CompileRequest request) {
        CompileResult result = LyraCompiler.compile(request);
        if (!(result instanceof CompileResult.Success success)) {
            throw new AssertionError("compile failed: " + result);
        }
        return success.artifact();
    }

    private static String captureOut(Runnable action) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            action.run();
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    private static Map<String, byte[]> entryMap(Path directory) throws Exception {
        LinkedHashMap<String, byte[]> result = new LinkedHashMap<>();
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                result.put(directory.relativize(path).toString().replace('\\', '/'),
                        Files.readAllBytes(path));
            }
        }
        return result;
    }

    private static void assertSameEntries(Map<String, byte[]> left, Map<String, byte[]> right) {
        assertEquals(left.keySet(), right.keySet());
        for (Map.Entry<String, byte[]> entry : left.entrySet()) {
            assertArrayEquals(entry.getValue(), right.get(entry.getKey()),
                    "entry bytes differ: " + entry.getKey());
        }
    }

    private static boolean contains(byte[] bytes, String value) {
        return new String(bytes, StandardCharsets.ISO_8859_1)
                .contains(value);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}
