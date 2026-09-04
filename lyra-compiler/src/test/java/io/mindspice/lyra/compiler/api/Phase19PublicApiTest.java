package io.mindspice.lyra.compiler.api;

import io.mindspice.lyra.runtime.ArtifactMetadata;
import io.mindspice.lyra.runtime.ArtifactSource;
import io.mindspice.lyra.runtime.LyraClosedException;
import io.mindspice.lyra.runtime.LyraLinkException;
import io.mindspice.lyra.runtime.LyraRuntime;
import io.mindspice.lyra.runtime.LyraRuntimeException;
import io.mindspice.lyra.runtime.LoadOptions;
import io.mindspice.lyra.runtime.LyraSignature;
import org.junit.jupiter.api.Test;

import javax.tools.ToolProvider;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class Phase19PublicApiTest {
    private static final String SOURCE = "let @pub answer :I32 = 42\n"
            + "let @pub add :Fn<I32,I32;I32> = (=> |left right| (+ left right))\n";

    @Test
    void compilerPublishesImmutableArtifactAndExactBoundHandle() throws Throwable {
        CompileResult result = LyraCompiler.compile(
                CompileRequest.source("main.lyra", SOURCE));
        CompileResult.Success success = assertInstanceOf(CompileResult.Success.class, result);
        CompiledArtifact artifact = success.artifact();
        assertTrue(success.diagnostics().isEmpty());
        assertEquals("path:main.lyra", artifact.metadata().rootModuleId().canonicalSpelling());

        Map<String, byte[]> first = artifact.entries();
        Map<String, byte[]> second = artifact.entries();
        assertNotSame(first, second);
        String className = artifact.classes().keySet().stream().findFirst().orElseThrow();
        String classEntry = className.replace('.', '/') + ".class";
        byte[] originalEntry = artifact.entries().get(classEntry).clone();
        first.get(classEntry)[0] ^= 1;
        assertArrayEquals(originalEntry, artifact.entries().get(classEntry));
        Map<String, byte[]> firstClasses = artifact.classes();
        byte[] originalClass = firstClasses.get(className).clone();
        firstClasses.get(className)[0] ^= 1;
        assertArrayEquals(originalClass, artifact.classes().get(className));

        var loaded = LyraRuntime.load(artifact, LoadOptions.defaults());
        var module = loaded.instantiate();
        var handle = module.export("add", LyraSignature.parse("Fn<I32,I32;I32>"));
        assertSame(handle, module.export("add", LyraSignature.parse("Fn<I32,I32;I32>")));
        assertEquals(handle.methodType(), handle.methodHandle().type());
        assertEquals(9, (int) handle.methodHandle().invokeExact(4, 5));
        assertThrows(IllegalArgumentException.class,
                () -> module.export("add", LyraSignature.parse("Fn<I32;I32>")));
        assertThrows(io.mindspice.lyra.runtime.LyraLifecycleException.class, loaded::close);
        module.close();
        loaded.close();
        assertThrows(LyraClosedException.class, () -> {
            int ignored = (int) handle.methodHandle().invokeExact(1, 2);
        });
    }

    @Test
    void authenticatedFunctionValuesShareOneLoadedArtifactDomain() throws Throwable {
        String source = "let @pub apply :Fn<Fn<I32;I32>,I32;I32> = (=> |f x| (f x))\n"
                + "let @pub inc :Fn<I32;I32> = (=> |x| (+ x 1))\n";
        CompileResult.Success result = assertInstanceOf(CompileResult.Success.class,
                LyraCompiler.compile(CompileRequest.source("main.lyra", source)));
        var loaded = LyraRuntime.load(result.artifact());
        var first = loaded.instantiate();
        var second = loaded.instantiate();
        try {
            var apply = first.export("apply", LyraSignature.parse("Fn<Fn<I32;I32>,I32;I32>"));
            var inc = second.export("inc", LyraSignature.parse("Fn<I32;I32>"));
            assertEquals(5, (int) apply.methodHandle().invoke(inc.functionValue(), 4));
        } finally {
            first.close();
            second.close();
            loaded.close();
        }
    }

    @Test
    void embeddedFacadeMetadataMatchesThePublishedArtifact() throws Throwable {
        CompileRequest request = CompileRequest.builder()
                .source("main.lyra", SOURCE)
                .includeSources(true)
                .semanticOptions(Map.of("checked-arithmetic", "on"))
                .build();
        CompileResult.Success result = assertInstanceOf(CompileResult.Success.class,
                LyraCompiler.compile(request));
        CompiledArtifact artifact = result.artifact();
        String facadeName = artifact.classes().keySet().stream()
                .filter(name -> name.contains(".$lyra$facade$"))
                .findFirst().orElseThrow();
        class DefiningLoader extends ClassLoader {
            DefiningLoader() {
                super(Phase19PublicApiTest.class.getClassLoader());
            }

            Class<?> define(String name, byte[] bytes) {
                return defineClass(name, bytes, 0, bytes.length);
            }
        }
        DefiningLoader loader = new DefiningLoader();
        for (Map.Entry<String, byte[]> entry : artifact.classes().entrySet()) {
            loader.define(entry.getKey(), entry.getValue());
        }
        Class<?> facade = Class.forName(facadeName, true, loader);
        ArtifactMetadata embedded = (ArtifactMetadata) facade
                .getMethod("$lyra$metadata").invoke(null);
        assertEquals(artifact.metadata(), embedded);
        assertEquals(artifact.metadata().canonicalJson(), embedded.canonicalJson());
    }

    @Test
    void repeatedCompilationWithTheSameOptionsIsByteForByteStable() {
        CompileRequest request = CompileRequest.builder()
                .source("main.lyra", SOURCE)
                .includeSources(true)
                .semanticOptions(Map.of("mode", "strict", "feature", "base"))
                .build();
        CompiledArtifact first = assertInstanceOf(CompileResult.Success.class,
                LyraCompiler.compile(request)).artifact();
        CompiledArtifact second = assertInstanceOf(CompileResult.Success.class,
                LyraCompiler.compile(request)).artifact();
        assertEquals(first.metadata().canonicalJson(), second.metadata().canonicalJson());
        assertEquals(first.entries().keySet(), second.entries().keySet());
        for (String name : first.entries().keySet()) {
            assertArrayEquals(first.entries().get(name), second.entries().get(name), name);
        }
    }

    @Test
    void generatedFacadeCanBeConsumedByAnOrdinaryJavaCompilation() throws Exception {
        CompileResult.Success result = assertInstanceOf(CompileResult.Success.class,
                LyraCompiler.compile(CompileRequest.source("main.lyra", SOURCE)));
        Path root = Files.createTempDirectory("lyra-java-consumer-");
        Path generated = root.resolve("generated");
        Path consumer = root.resolve("consumer");
        Files.createDirectories(consumer);
        try {
            result.artifact().writeClasses(generated);
            String facadeName = result.artifact().classes().keySet().stream()
                    .filter(name -> name.contains(".$lyra$facade$"))
                    .findFirst().orElseThrow();
            String source = "public final class Consumer {"
                    + " public static int run() {"
                    + "   " + facadeName + " module = " + facadeName + ".$lyra$create();"
                    + "   try { return module.add(4, 5); } finally { module.close(); }"
                    + " }"
                    + "}";
            Path sourceFile = root.resolve("Consumer.java");
            Files.writeString(sourceFile, source);
            var compiler = ToolProvider.getSystemJavaCompiler();
            assertTrue(compiler != null, "the test JVM must provide javac");
            String classPath = generated + java.io.File.pathSeparator
                    + System.getProperty("java.class.path");
            int exit = compiler.run(null, null, null, "--release", "25", "-classpath", classPath,
                    "-d", consumer.toString(), sourceFile.toString());
            assertEquals(0, exit);
            try (URLClassLoader loader = new URLClassLoader(
                    new URL[] {consumer.toUri().toURL(), generated.toUri().toURL()},
                    Phase19PublicApiTest.class.getClassLoader())) {
                Class<?> consumerClass = Class.forName("Consumer", true, loader);
                Method run = consumerClass.getMethod("run");
                assertEquals(9, run.invoke(null));
            }
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void pathRootCompilationAndInMemoryLoadingArePubliclyEquivalent() throws Throwable {
        Path root = Files.createTempDirectory("lyra-path-root-");
        Path source = root.resolve("main.lyra");
        Files.writeString(source, SOURCE);
        try {
            CompileResult.Success pathResult = assertInstanceOf(CompileResult.Success.class,
                    LyraCompiler.compile(CompileRequest.path(source)));
            CompileResult.Success memoryResult = assertInstanceOf(CompileResult.Success.class,
                    LyraCompiler.compile(CompileRequest.source("main.lyra", SOURCE)));
            assertEquals(pathResult.artifact().metadata().rootModuleId(),
                    memoryResult.artifact().metadata().rootModuleId());
            invokeAndClose(LyraRuntime.load(pathResult.artifact()));
            invokeAndClose(LyraRuntime.load(memoryResult.artifact()));
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void incompatibleRuntimeProfilesFailBeforeClassDefinition() {
        CompileResult.Success result = assertInstanceOf(CompileResult.Success.class,
                LyraCompiler.compile(CompileRequest.source("main.lyra", SOURCE)));
        var profile = new io.mindspice.lyra.runtime.RuntimeProfile(
                "java-24", 24, true, io.mindspice.lyra.runtime.RuntimeAbi.CURRENT);
        LoadOptions options = LoadOptions.defaults().withProfile(profile);
        LyraRuntimeException failure = assertThrows(LyraRuntimeException.class,
                () -> LyraRuntime.load(result.artifact(), options));
        assertEquals("LYR-COMPAT", failure.code());
    }

    @Test
    void reservedFacadeInvocationNamesRemainUsableThroughThePublicApi() throws Throwable {
        CompileResult.Success result = assertInstanceOf(CompileResult.Success.class,
                LyraCompiler.compile(CompileRequest.source("reserved.lyra",
                        "let @pub close :Fn<I32;I32> = (=> |x| (+ x 1))")));
        var metadata = result.artifact().metadata().exports().getFirst();
        assertEquals("invoke$close", metadata.javaName());
        var loaded = LyraRuntime.load(result.artifact());
        var module = loaded.instantiate();
        try {
            var handle = module.export("close", "Fn<I32;I32>");
            assertEquals(3, (int) handle.methodHandle().invokeExact(2));
        } finally {
            module.close();
            loaded.close();
        }
    }

    @Test
    void sourceFailuresNeverPublishAnArtifact() {
        CompileResult result = LyraCompiler.compile(
                CompileRequest.source("bad.lyra", "let ="));
        CompileResult.Failure failure = assertInstanceOf(CompileResult.Failure.class, result);
        assertEquals(1, failure.diagnostics().size());
        assertTrue(failure.diagnostics().getFirst().code().value().startsWith("LYC-"));
    }

    @Test
    void classDirectoryAndThinJarLoadThroughTheSameBoundary() throws Throwable {
        CompileResult.Success result = assertInstanceOf(CompileResult.Success.class,
                LyraCompiler.compile(CompileRequest.source("main.lyra", SOURCE)));
        Path root = Files.createTempDirectory("lyra-phase19-");
        Path classes = root.resolve("classes");
        Path jar = root.resolve("app.jar");
        try {
            result.artifact().writeClasses(classes);
            result.artifact().writeJar(jar, JarMode.THIN);
            invokeAndClose(LyraRuntime.load(classes));
            invokeAndClose(LyraRuntime.load(jar));
        } finally {
            try (var paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    @Test
    void definitionBoundaryTranslatesBrokenClassLinkage() {
        CompileResult.Success result = assertInstanceOf(CompileResult.Success.class,
                LyraCompiler.compile(CompileRequest.source("main.lyra", SOURCE)));
        Map<String, byte[]> entries = new LinkedHashMap<>(result.artifact().entries());
        String classEntry = entries.keySet().stream().filter(name -> name.endsWith(".class"))
                .findFirst().orElseThrow();
        byte[] broken = entries.get(classEntry).clone();
        broken[20] ^= 1;
        entries.put(classEntry, broken);
        ArtifactSource brokenArtifact = ArtifactSource.fromEntries(
                result.artifact().metadata(), entries);
        LyraRuntimeException failure = assertThrows(LyraRuntimeException.class,
                () -> LyraRuntime.load(brokenArtifact));
        assertEquals("LYR-LINK", failure.code());
    }

    private static void invokeAndClose(io.mindspice.lyra.runtime.LoadedArtifact loaded)
            throws Throwable {
        var module = loaded.instantiate();
        assertEquals(9, (int) module.export("add", "Fn<I32,I32;I32>")
                .methodHandle().invokeExact(4, 5));
        module.close();
        loaded.close();
    }

    private static void deleteTree(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            });
        }
    }
}
