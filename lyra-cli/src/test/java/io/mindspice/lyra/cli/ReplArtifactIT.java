package io.mindspice.lyra.cli;

import io.mindspice.lyra.compiler.api.CompileRequest;
import io.mindspice.lyra.compiler.api.CompileResult;
import io.mindspice.lyra.compiler.api.CompiledArtifact;
import io.mindspice.lyra.compiler.api.JarMode;
import io.mindspice.lyra.compiler.api.LyraCompiler;
import io.mindspice.lyra.runtime.ArtifactDependency;
import io.mindspice.lyra.runtime.ArtifactMetadataReader;
import io.mindspice.lyra.runtime.ArtifactProfile;
import io.mindspice.lyra.runtime.LyraRuntimeConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deployment matrix for deterministic debug artifact packaging: bundled
 * {@code java -jar} closure, explicit-location classes/thin layouts,
 * missing-dependency diagnostics, inventory exclusions, and byte-identical
 * repeat builds.
 */
@Timeout(120)
final class ReplArtifactIT {
    private static final String MAIN_SOURCE =
            "let @pub main :Fn<Array<String>;I32> = (=> |args| 7)\n";

    @TempDir
    Path temp;

    @Test
    void bundledDebugJarRunsWithNoExternalCompilerOrReplJars() throws Exception {
        Path artifact = temp.resolve("app.jar");
        CompiledArtifact compiled = compileDebug();
        compiled.writeJar(artifact, JarMode.BUNDLED_JAR);
        try (JarFile jar = new JarFile(artifact.toFile())) {
            assertEquals("io.mindspice.lyra.repl.ReplLauncher",
                    jar.getManifest().getMainAttributes().getValue("Main-Class"));
            List<String> names = jar.stream().map(ZipEntry::getName).toList();
            assertEquals(names.stream().sorted().toList(), names);
            assertTrue(jar.stream().noneMatch(ZipEntry::isDirectory));
            assertTrue(jar.stream().allMatch(entry ->
                    entry.getMethod() == ZipEntry.STORED));
            // The actual production closure is physically inside.
            assertNotNull(jar.getJarEntry("io/mindspice/lyra/compiler/api/LyraCompiler.class"));
            assertNotNull(jar.getJarEntry("io/mindspice/lyra/repl/ReplLauncher.class"));
            assertNotNull(jar.getJarEntry("io/mindspice/lyra/runtime/LyraLauncher.class"));
            assertTrue(jar.stream().anyMatch(entry -> entry.getName()
                    .startsWith("io/mindspice/lyra/compiler/")));
            assertTrue(jar.stream().anyMatch(entry -> entry.getName()
                    .startsWith("io/mindspice/lyra/repl/")));
            // CLI, JLine, test, and credential material is excluded.
            assertTrue(jar.stream().noneMatch(entry -> entry.getName()
                    .startsWith("io/mindspice/lyra/cli/")));
            assertTrue(jar.stream().noneMatch(entry -> entry.getName()
                    .contains("jline") || entry.getName().startsWith("org/junit/")
                    || entry.getName().contains("test-classes")
                    || entry.getName().startsWith("META-INF/services/")));
            assertTrue(jar.stream().noneMatch(entry -> !entry.getName().endsWith(".class")
                    && !entry.getName().equals("META-INF/MANIFEST.MF")
                    && !entry.getName().equals("META-INF/lyra/artifact.json")
                    && !entry.getName().equals("META-INF/lyra/debug-map.json")
                    && !entry.getName().startsWith("META-INF/lyra/sources/")));
        }
        ProcessResult result = process(java(), "-Xverify:all", "-jar", artifact.toString());
        assertEquals(7, result.exit(), result.output());
        assertEquals("", result.output(), "debug launcher wrote unexpected output");
    }

    @Test
    void bundledDebugClosurePreflightRunsAndCompilesInProcess() throws Exception {
        // The successful run above proves the closure by exit code only;
        // assert the preflight really executed by stripping the compiler
        // closure and observing the actionable inventory error.
        Path artifact = temp.resolve("app.jar");
        compileDebug().writeJar(artifact, JarMode.BUNDLED_JAR);
        Path stripped = temp.resolve("stripped.jar");
        stripEntries(artifact, stripped, name -> name.startsWith("io/mindspice/lyra/compiler/"));
        ProcessResult result = process(java(), "-Xverify:all", "-jar", stripped.toString());
        assertNotEquals(0, result.exit());
        assertTrue(result.output().contains("lyra-compiler"), result.output());
        assertTrue(result.output().contains("missing the production"),
                "missing closure must be an actionable error: " + result.output());
    }

    @Test
    void debugClassesAndThinJarsRunThroughTheExplicitLocationLauncher() throws Exception {
        CompiledArtifact compiled = compileDebug();
        Path classes = temp.resolve("debug-classes");
        compiled.writeClasses(classes);
        String classpath = join(runtimeCodeSource(), compilerCodeSource(),
                replCodeSource(), classes);
        ProcessResult classesResult = process(java(), "-Xverify:all", "-cp", classpath,
                "io.mindspice.lyra.repl.ReplLauncher", classes.toString());
        assertEquals(7, classesResult.exit(), classesResult.output());

        Path thin = temp.resolve("debug-thin.jar");
        compiled.writeJar(thin, JarMode.THIN_JAR);
        // Thin publications document the exact external closure.
        try (JarFile jar = new JarFile(thin.toFile())) {
            assertEquals(List.of(
                    new ArtifactDependency("io.mindspice", "lyra-compiler",
                            LyraRuntimeConstants.COMPILER_VERSION, ArtifactProfile.NORMAL),
                    new ArtifactDependency("io.mindspice", "lyra-repl",
                            LyraRuntimeConstants.REPL_VERSION, ArtifactProfile.NORMAL),
                    new ArtifactDependency("io.mindspice", "lyra-runtime",
                            LyraRuntimeConstants.RUNTIME_VERSION, ArtifactProfile.NORMAL)),
                    ArtifactMetadataReader.read(jar.getInputStream(jar.getJarEntry(
                            "META-INF/lyra/artifact.json")).readAllBytes()).dependencyRequirements());
        }
        String thinClasspath = join(runtimeCodeSource(), compilerCodeSource(),
                replCodeSource(), thin);
        ProcessResult thinResult = process(java(), "-Xverify:all", "-cp", thinClasspath,
                "io.mindspice.lyra.repl.ReplLauncher", thin.toString());
        assertEquals(7, thinResult.exit(), thinResult.output());
    }

    @Test
    void missingExternalCompilerDependencyIsAnActionableError() throws Exception {
        CompiledArtifact compiled = compileDebug();
        Path classes = temp.resolve("debug-classes");
        compiled.writeClasses(classes);
        String classpath = join(runtimeCodeSource(), replCodeSource(), classes);
        ProcessResult result = process(java(), "-cp", classpath,
                "io.mindspice.lyra.repl.ReplLauncher", classes.toString());
        assertNotEquals(0, result.exit());
        assertTrue(result.output().contains("lyra-compiler")
                        || result.output().contains("LyraCompiler"),
                "missing external dependency must name lyra-compiler: " + result.output());
    }

    @Test
    void debugBundledBuildsAreByteIdenticalAcrossRepeatPackaging() throws Exception {
        CompiledArtifact compiled = compileDebug();
        Path first = temp.resolve("first.jar");
        Path second = temp.resolve("second.jar");
        compiled.writeJar(first, JarMode.BUNDLED_JAR);
        compiled.writeJar(second, JarMode.BUNDLED_JAR);
        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));
    }

    @Test
    void ordinaryBundledArtifactsRemainClosureFreeAndRunUnchanged() throws Exception {
        CompileResult result = LyraCompiler.compile(CompileRequest.builder()
                .source("normal.lyra", MAIN_SOURCE).build());
        if (!(result instanceof CompileResult.Success success)) {
            throw new AssertionError("compile failed: " + result);
        }
        CompiledArtifact compiled = success.artifact();
        Path artifact = temp.resolve("normal.jar");
        compiled.writeJar(artifact, JarMode.BUNDLED_JAR);
        try (JarFile jar = new JarFile(artifact.toFile())) {
            assertEquals("io.mindspice.lyra.runtime.LyraLauncher",
                    jar.getManifest().getMainAttributes().getValue("Main-Class"));
            assertTrue(jar.stream().noneMatch(entry -> entry.getName()
                    .startsWith("io/mindspice/lyra/compiler/")
                    || entry.getName().startsWith("io/mindspice/lyra/repl/")));
            assertTrue(jar.stream().anyMatch(entry -> entry.getName()
                    .startsWith("io/mindspice/lyra/runtime/")));
        }
        ProcessResult result2 = process(java(), "-Xverify:all", "-jar", artifact.toString());
        assertEquals(7, result2.exit(), result2.output());
    }

    @Test
    void debugMetadataNeverRecordsActivationOrTimingValues() {
        CompiledArtifact compiled = compileDebug();
        String json = compiled.metadata().canonicalJson();
        for (String forbidden : List.of("port", "endpoint", "wait", "listen", "host", "time")) {
            assertFalse(json.contains("\"" + forbidden), forbidden + " entered artifact bytes");
        }
        assertTrue(compiled.metadata().replCapable());
    }

    private static CompiledArtifact compileDebug() {
        CompileResult result = LyraCompiler.compile(CompileRequest.builder()
                .source("debug.lyra", MAIN_SOURCE)
                .debugCapable(true)
                .build());
        if (!(result instanceof CompileResult.Success success)) {
            throw new AssertionError("compile failed: " + result);
        }
        return success.artifact();
    }

    private static void stripEntries(Path source, Path target,
                                     java.util.function.Predicate<String> drop)
            throws IOException {
        try (JarFile jar = new JarFile(source.toFile());
             ZipOutputStream output = new ZipOutputStream(
                     Files.newOutputStream(target))) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (drop.test(entry.getName())) {
                    continue;
                }
                byte[] bytes = jar.getInputStream(entry).readAllBytes();
                ZipEntry copy = new ZipEntry(entry.getName());
                CRC32 crc = new CRC32();
                crc.update(bytes);
                copy.setCrc(crc.getValue());
                copy.setSize(bytes.length);
                copy.setMethod(ZipEntry.STORED);
                output.putNextEntry(copy);
                output.write(bytes);
                output.closeEntry();
            }
        }
    }

    private static Path runtimeCodeSource() {
        return minimalCodeSource(io.mindspice.lyra.runtime.LyraRuntime.class,
                LyraCompiler.class);
    }

    private static Path compilerCodeSource() {
        return minimalCodeSource(LyraCompiler.class, LyraCli.class);
    }

    private static Path replCodeSource() {
        return minimalCodeSource(io.mindspice.lyra.repl.LyraSession.class,
                LyraCompiler.class);
    }

    /**
     * Resolves the production location of one module and rejects fat
     * distributions (the shaded CLI jar carries every module): the selected
     * location must contain the anchor class and must not contain the
     * exclusion marker.  This keeps explicit-layout launcher runs honest in
     * both surefire (class directories) and failsafe (packaged jars).
     */
    private static Path minimalCodeSource(Class<?> anchor, Class<?> excludedMarker) {
        String entry = anchor.getName().replace('.', '/') + ".class";
        ArrayList<java.net.URL> candidates = new ArrayList<>();
        try {
            java.util.Enumeration<java.net.URL> resources =
                    ClassLoader.getSystemResources(entry);
            while (resources.hasMoreElements()) {
                candidates.add(resources.nextElement());
            }
            for (java.net.URL resource : candidates) {
                Path location = fileLocation(resource, entry);
                if (location == null || !containsClass(location, anchor)) {
                    continue;
                }
                if (excludedMarker != null && containsClass(location, excludedMarker)) {
                    continue;
                }
                return location;
            }
            throw new AssertionError("cannot resolve the minimal production location for "
                    + anchor.getName() + " among " + candidates);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static Path fileLocation(java.net.URL resource, String entry) {
        try {
            String spelling = resource.toString();
            if ("jar".equals(resource.getProtocol())) {
                return Path.of(java.net.URI.create(spelling.substring(
                        "jar:".length(), spelling.indexOf("!/")))).toAbsolutePath();
            }
            if ("file".equals(resource.getProtocol())) {
                Path location = Path.of(resource.toURI());
                for (int segments = entry.split("/").length; segments > 0; segments--) {
                    location = location.getParent();
                    if (location == null) {
                        return null;
                    }
                }
                return location.toAbsolutePath();
            }
            return null;
        } catch (Exception failure) {
            return null;
        }
    }

    private static boolean containsClass(Path location, Class<?> type) throws Exception {
        String entry = type.getName().replace('.', '/') + ".class";
        if (Files.isDirectory(location)) {
            return Files.isRegularFile(location.resolve(entry));
        }
        try (JarFile jar = new JarFile(location.toFile())) {
            return jar.getJarEntry(entry) != null;
        }
    }

    private static String join(Path... paths) {
        ArrayList<String> parts = new ArrayList<>();
        for (Path path : paths) {
            parts.add(path.toString());
        }
        return String.join(java.io.File.pathSeparator, parts);
    }

    private static String java() {
        return JLinePtyTest.javaCommand();
    }

    private ProcessResult process(String... args) throws Exception {
        Path log = temp.resolve("process-" + System.nanoTime() + ".log");
        Process process = new ProcessBuilder(args).directory(temp.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("subprocess timed out: " + String.join(" ", args));
            }
            return new ProcessResult(process.exitValue(), Files.readString(log));
        } finally {
            process.destroyForcibly();
        }
    }

    private record ProcessResult(int exit, String output) {
    }
}
