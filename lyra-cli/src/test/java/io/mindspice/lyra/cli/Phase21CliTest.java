package io.mindspice.lyra.cli;

import io.mindspice.lyra.compiler.api.CompileRequest;
import io.mindspice.lyra.compiler.api.CompileResult;
import io.mindspice.lyra.compiler.api.CompiledArtifact;
import io.mindspice.lyra.compiler.api.LyraCompiler;
import io.mindspice.lyra.runtime.LyraRuntime;
import io.mindspice.lyra.runtime.LyraRuntimeConstants;
import io.mindspice.lyra.runtime.LoadedArtifact;
import io.mindspice.lyra.runtime.ModuleHandle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class Phase21CliTest {
    private static final String MAIN =
            "let @pub main :Fn<Array<String>;I32> = (=> |args| args:.length)\n";
    private static final String PRINTING_MAIN =
            "import std->io as io "
                    + "let @pub main :Fn<Array<String>;I32> = "
                    + "(=> |args| { io->::println[\"hello\"] 0 })\n";

    @TempDir
    Path temp;

    @Test
    void helpVersionAndUsageAreStable() {
        Invocation help = invoke("--help");
        assertEquals(0, help.status());
        assertEquals(LyraCli.HELP_TEXT + "\n", help.stdout());
        assertEquals("", help.stderr());

        Invocation version = invoke("--version");
        assertEquals(0, version.status());
        assertEquals(LyraCli.VERSION_TEXT + "\n", version.stdout());
        assertEquals("", version.stderr());

        Invocation missing = invoke();
        assertEquals(2, missing.status());
        assertEquals("", missing.stdout());
        assertEquals("lyra: error: missing command\n" + LyraCli.USAGE_TEXT + "\n",
                missing.stderr());

        Invocation unknown = invoke("wat");
        assertEquals(2, unknown.status());
        assertTrue(unknown.stderr().startsWith("lyra: error: unknown command: wat\n"));
        assertTrue(unknown.stderr().endsWith(LyraCli.USAGE_TEXT + "\n"));
    }

    @Test
    void replSupportsEmptyAndConfiguredPlainWorkspacesWithoutChangingRunCompile() {
        Invocation empty = invoke(new String[] {"repl", "--plain"},
                new ByteArrayInputStream(new byte[0]),
                new ByteArrayOutputStream(), new ByteArrayOutputStream());
        assertEquals(0, empty.status());
        assertEquals("", empty.stdout());
        assertEquals("", empty.stderr());

        Invocation implicitPlain = invoke(new String[] {"repl"},
                new ByteArrayInputStream(":quit\n".getBytes(StandardCharsets.UTF_8)),
                new ByteArrayOutputStream(), new ByteArrayOutputStream());
        assertEquals(0, implicitPlain.status());
        assertEquals("", implicitPlain.stdout());
        assertEquals("", implicitPlain.stderr());

        Path sourceRoot = temp.resolve("configured source root");
        try {
            Files.createDirectories(sourceRoot);
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
        Invocation configured = invoke(new String[] {"repl", sourceRoot.toString(), "--plain"},
                new ByteArrayInputStream(":quit\n".getBytes(StandardCharsets.UTF_8)),
                new ByteArrayOutputStream(), new ByteArrayOutputStream());
        assertEquals(0, configured.status());
        assertEquals("", configured.stdout());
        assertEquals("", configured.stderr());

        Path historyFile = temp.resolve("history.txt");
        Invocation history = invoke(new String[] {"repl", "--history", historyFile.toString()},
                new ByteArrayInputStream(new byte[0]),
                new ByteArrayOutputStream(), new ByteArrayOutputStream());
        assertEquals(0, history.status(), history.stderr());
        assertTrue(Files.isRegularFile(historyFile));

        Path notDirectory = source("not-a-directory.lyra", "");
        Invocation invalidRoot = invoke("repl", notDirectory.toString());
        assertEquals(2, invalidRoot.status());
        assertTrue(invalidRoot.stderr().contains("existing directory"));
    }

    @Test
    void replAcceptsRepeatableSourceRootsAndExecutesThroughTheManagedOwner()
            throws IOException {
        Path firstRoot = Files.createDirectory(temp.resolve("first root"));
        Path secondRoot = Files.createDirectory(temp.resolve("second root"));
        Invocation result = invoke(new String[] {
                        "repl", "--source-root", firstRoot.toString(),
                        "--source-root", secondRoot.toString(), "--plain"},
                new ByteArrayInputStream(
                        ("let @pub answer :I32 = (+ 40 2)\n"
                                + ":bindings\n:quit\n").getBytes(StandardCharsets.UTF_8)),
                new ByteArrayOutputStream(), new ByteArrayOutputStream());
        assertEquals(0, result.status(), result.stderr());
        assertTrue(result.stdout().contains("answer :I32\n"), result.stdout());
        assertEquals("", result.stderr());

        Path notDirectory = source("not-a-dir.lyra", "");
        Invocation invalid = invoke("repl", "--source-root", notDirectory.toString());
        assertEquals(2, invalid.status());
        assertTrue(invalid.stderr().contains("existing directory"), invalid.stderr());

        Invocation missingValue = invoke("repl", "--source-root");
        assertEquals(2, missingValue.status());
        assertTrue(missingValue.stderr().contains(LyraCli.USAGE_TEXT), missingValue.stderr());
    }

    @Test
    void replKeymapsAreValidatedAndInjectedStreamsAlwaysStayPlain() {
        for (String keymap : List.of("emacs", "vi")) {
            Invocation result = invoke(new String[] {"repl", "--keymap", keymap},
                    new ByteArrayInputStream(":help\n:quit\n".getBytes(StandardCharsets.UTF_8)),
                    new ByteArrayOutputStream(), new ByteArrayOutputStream());
            assertEquals(0, result.status());
            assertEquals(io.mindspice.lyra.repl.PlainConsole.HELP_TEXT, result.stdout());
            assertEquals("", result.stderr());
        }
        for (String[] arguments : List.of(
                new String[] {"repl", "--keymap"},
                new String[] {"repl", "--keymap", "vim"},
                new String[] {"repl", "--keymap", ""},
                new String[] {"repl", "--keymap", "vi", "--keymap", "emacs"},
                new String[] {"repl", "--history", "one", "--history", "two"},
                new String[] {"repl", "--plain", "--plain"})) {
            assertEquals(2, invoke(arguments).status());
        }
    }

    @Test
    void replUsesInjectedStreamsWithoutClosingCallerResources() throws IOException {
        TrackingInput input = new TrackingInput(":quit\n");
        TrackingOutput output = new TrackingOutput();
        TrackingOutput error = new TrackingOutput();

        assertEquals(0, LyraCli.execute(new String[] {"repl"}, input, output, error));
        assertFalse(input.closed);
        assertFalse(output.closed);
        assertFalse(error.closed);
    }

    @Test
    void parserRejectsUnknownMissingAndDuplicateCompileOptions() {
        Path root = source("main.lyra", MAIN);
        for (String[] arguments : List.of(
                new String[] {"run", root.toString(), "--unknown"},
                new String[] {"run", root.toString(), "--source-root"},
                new String[] {"compile", root.toString(), "--format"},
                new String[] {"compile", root.toString(), "--format", "wat"},
                new String[] {"compile", root.toString(), "--output", "a", "--output", "b"},
                new String[] {"compile", root.toString(), "--force", "--force"},
                new String[] {"compile", root.toString(), "--"})) {
            Invocation failure = invoke(arguments);
            assertEquals(2, failure.status(), String.join(" ", arguments));
            assertEquals("", failure.stdout());
            assertTrue(failure.stderr().contains(LyraCli.USAGE_TEXT), failure.stderr());
        }

        Invocation nullToken = invoke(new String[] {"run", null});
        assertEquals(2, nullToken.status());
        assertTrue(nullToken.stderr().contains(LyraCli.USAGE_TEXT));
    }

    @Test
    void runForwardsOnlyTokensAfterSeparatorAndDoesNotPublishFiles() throws IOException {
        Path root = source("args.lyra", MAIN);
        List<Path> before = files(temp);
        Invocation run = invoke("run", root.toString(), "--source-root", temp.toString(), "--",
                "--source-root", "not-a-root", "a b");
        assertEquals(3, run.status());
        assertEquals("", run.stdout());
        assertEquals("", run.stderr());
        assertEquals(before, files(temp));
    }

    @Test
    void runUsesInMemoryLifecycleAndConfiguresUtf8Io() {
        Path root = source("printing.lyra", PRINTING_MAIN);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int status = LyraCli.execute(new String[] {"run", root.toString()},
                new ByteArrayInputStream(new byte[0]), output, error);
        assertEquals(0, status);
        assertEquals("hello\n", output.toString(StandardCharsets.UTF_8));
        assertEquals("", error.toString(StandardCharsets.UTF_8));
    }

    @Test
    void logicalRootsResolveThroughExplicitSourceRoots() {
        Path sourceRoot = temp.resolve("sources");
        Path root = sourceRoot.resolve("demo/main.lyra");
        Path dependency = sourceRoot.resolve("demo/value.lyra");
        write(root, "import demo->value "
                + "let @pub main :Fn<Array<String>;I32> = (=> |args| value->:.answer)\n");
        write(dependency, "let @pub answer :I32 = 23\n");

        Invocation result = invoke("run", "demo->main", "--source-root", sourceRoot.toString());
        assertEquals(23, result.status());
        assertEquals("", result.stdout());
        assertEquals("", result.stderr());
    }

    @Test
    void compileSupportsClassesThinAndBundledOutputs() throws Throwable {
        Path root = source("app.lyra", "let @pub main :Fn<Array<String>;I32> = (=> |args| 7)\n");
        Path classes = temp.resolve("classes");
        Path thin = temp.resolve("app-thin.jar");
        Path bundled = temp.resolve("app.jar");

        assertEquals(0, invoke("compile", root.toString(), "--format", "classes",
                "--output", classes.toString(), "--include-sources").status());
        assertTrue(Files.isRegularFile(classes.resolve(LyraRuntimeConstants.ARTIFACT_METADATA_PATH)));
        assertTrue(Files.isRegularFile(classes.resolve(LyraRuntimeConstants.DEBUG_MAP_PATH)));
        assertTrue(Files.exists(classes.resolve("META-INF/lyra/sources/app.lyra")));
        invokeAndClose(LyraRuntime.load(classes));

        assertEquals(0, invoke("compile", root.toString(), "--format", "thin-jar",
                "--output", thin.toString()).status());
        assertEquals(0, invoke("compile", root.toString(), "--format", "bundled-jar",
                "--output", bundled.toString()).status());

        invokeAndClose(LyraRuntime.load(thin));
        invokeAndClose(LyraRuntime.load(bundled));

        Map<String, byte[]> classDirectory = classEntries(classes);
        try (JarFile jar = new JarFile(bundled.toFile())) {
            assertEquals("io.mindspice.lyra.runtime.LyraLauncher",
                    jar.getManifest().getMainAttributes().getValue("Main-Class"));
            assertTrue(jar.stream().noneMatch(entry -> entry.isDirectory()));
            assertTrue(jar.stream().anyMatch(entry -> entry.getName()
                    .equals("io/mindspice/lyra/runtime/LyraLauncher.class")));
            assertTrue(jar.stream().noneMatch(entry -> entry.getName().contains("lyra/compiler")));
            assertTrue(jar.stream().noneMatch(entry -> entry.getName().contains("jline")
                    || entry.getName().contains("lyra/repl") || entry.getName().contains("lyra/cli")));
            for (Map.Entry<String, byte[]> entry : classDirectory.entrySet()) {
                // The facade embeds the mode-specific canonical artifact
                // metadata.  All executable/helper classes and the debug map
                // must remain identical across packaging modes.
                if (entry.getKey().contains("/$lyra$facade$")) {
                    continue;
                }
                var jarEntry = jar.getJarEntry(entry.getKey());
                assertNotNull(jarEntry, entry.getKey());
                assertArrayEquals(entry.getValue(), jar.getInputStream(jarEntry).readAllBytes(),
                        entry.getKey());
            }
            assertArrayEquals(Files.readAllBytes(classes.resolve(
                    LyraRuntimeConstants.DEBUG_MAP_PATH)),
                    jar.getInputStream(jar.getJarEntry(LyraRuntimeConstants.DEBUG_MAP_PATH))
                            .readAllBytes());
        }
    }

    @Test
    void compileDefaultsToTheStableBundledOutputPath() throws Exception {
        Path root = source("app.lyra", "let @pub main :Fn<Array<String>;I32> = (=> |args| 7)\n");
        ProcessResult result = process(temp, javaCommand(), "-cp", System.getProperty("java.class.path"),
                LyraCli.class.getName(), "compile", root.toString());
        assertEquals(0, result.status());
        assertEquals("", result.stdout());
        assertEquals("", result.stderr());
        assertTrue(Files.isRegularFile(temp.resolve("build/lyra/app.jar")));
    }

    @Test
    void bundledJarExecutesWithJvmVerificationAndPreservesReturnedStatus() throws Exception {
        Path root = source("app.lyra", "let @pub main :Fn<Array<String>;I32> = (=> |args| 7)\n");
        Path bundled = temp.resolve("verify.jar");
        assertEquals(0, invoke("compile", root.toString(), "--output", bundled.toString()).status());

        ProcessResult result = process(temp, javaCommand(), "-Xverify:all", "-jar",
                bundled.toString());
        assertEquals(7, result.status());
        assertEquals("", result.stdout());
        assertEquals("", result.stderr());
    }

    @Test
    void bundledJarPassesLiteralSeparatorToMain() throws Exception {
        Path root = source("args.lyra", MAIN);
        Path bundled = temp.resolve("args.jar");
        assertEquals(0, invoke("compile", root.toString(), "--output", bundled.toString()).status());

        ProcessResult result = process(temp, javaCommand(), "-jar", bundled.toString(), "--", "value");
        assertEquals(2, result.status());
        assertEquals("", result.stdout());
        assertEquals("", result.stderr());
    }

    @Test
    void outputRefusalIsNonDestructiveAndForceReplacesAtomically() throws Exception {
        Path root = source("app.lyra", MAIN);
        Path output = temp.resolve("repeat.jar");
        assertEquals(0, invoke("compile", root.toString(), "--output", output.toString()).status());
        byte[] original = Files.readAllBytes(output);

        Invocation refused = invoke("compile", root.toString(), "--output", output.toString());
        assertEquals(2, refused.status());
        assertArrayEquals(original, Files.readAllBytes(output));
        assertTrue(files(temp).stream().noneMatch(path -> path.getFileName().toString()
                .contains(".lyra-staging-")));

        assertEquals(0, invoke("compile", root.toString(), "--output", output.toString(),
                "--force").status());
        assertArrayEquals(original, Files.readAllBytes(output));
    }

    @Test
    void bundledCompileAndRunRejectRootsWithoutTheExactPublicMain() {
        Path root = source("library.lyra", "let @pub value :I32 = 1\n");
        Path bundled = temp.resolve("library.jar");

        Invocation compile = invoke("compile", root.toString(), "--output", bundled.toString());
        assertEquals(1, compile.status());
        assertTrue(compile.stderr().contains("LYC-PACKAGE-001"));
        assertFalse(Files.exists(bundled));

        Invocation run = invoke("run", root.toString());
        assertEquals(1, run.status());
        assertTrue(run.stderr().contains("LYC-PACKAGE-001"));

        Invocation sourceFailure = invoke("run", source("broken.lyra", "let =").toString());
        assertEquals(1, sourceFailure.status());
        assertTrue(sourceFailure.stderr().contains("LYC-"));
    }

    @Test
    void compileConfigurationErrorsUseConfigurationExitClass() {
        Path root = source("app.lyra", MAIN);
        Invocation invalidPackage = invoke("compile", root.toString(), "--java-package", "java");
        assertEquals(2, invalidPackage.status());
        assertTrue(invalidPackage.stderr().contains("LYC-MODULE-001"));
    }

    @Test
    void scriptsAreSelfResolvingAndForwardArguments() throws IOException {
        Path unix = Path.of("src/main/scripts/lyra");
        Path windows = Path.of("src/main/scripts/lyra.bat");
        assertTrue(Files.isExecutable(unix));
        String unixText = Files.readString(unix);
        String windowsText = Files.readString(windows);
        assertTrue(unixText.contains("exec \"$JAVA_COMMAND\""));
        assertTrue(unixText.contains("\"$@\""));
        assertTrue(windowsText.contains("%*"));
        assertTrue(windowsText.contains("%~dp0"));
        assertTrue(windowsText.contains("JAVA_COMMAND"));
        assertTrue(unixText.contains("--enable-native-access=ALL-UNNAMED"));
        assertTrue(windowsText.contains("--enable-native-access=ALL-UNNAMED"));
    }

    private Invocation invoke(String... arguments) {
        return invoke(arguments, new ByteArrayInputStream(new byte[0]),
                new ByteArrayOutputStream(), new ByteArrayOutputStream());
    }

    private Invocation invoke(String[] arguments, java.io.InputStream input,
                              java.io.OutputStream output, java.io.OutputStream error) {
        int status = LyraCli.execute(arguments, input, output, error);
        return new Invocation(status, text(output), text(error));
    }

    private static String text(java.io.OutputStream output) {
        if (output instanceof ByteArrayOutputStream bytes) {
            return bytes.toString(StandardCharsets.UTF_8);
        }
        return "";
    }

    private Path source(String name, String contents) {
        Path path = temp.resolve(name);
        write(path, contents);
        return path;
    }

    private static void write(Path path, String contents) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, contents, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new RuntimeException(failure);
        }
    }

    private static List<Path> files(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.sorted().toList();
        }
    }

    private static Map<String, byte[]> classEntries(Path classes) throws IOException {
        Map<String, byte[]> result = new HashMap<>();
        try (var paths = Files.walk(classes)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String name = classes.relativize(path).toString().replace('\\', '/');
                if (name.endsWith(".class")) {
                    result.put(name, Files.readAllBytes(path));
                }
            }
        }
        return result;
    }

    private static void invokeAndClose(LoadedArtifact loaded) throws Throwable {
        ModuleHandle module = loaded.instantiate();
        try {
            assertEquals(7, (int) module.export("main", "Fn<Array<String>;I32>")
                    .methodHandle().invokeExact(new String[0]));
        } finally {
            module.close();
            loaded.close();
        }
    }

    private ProcessResult process(Path directory, String... command) throws IOException,
            InterruptedException {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).start();
        byte[] stdout = process.getInputStream().readAllBytes();
        byte[] stderr = process.getErrorStream().readAllBytes();
        return new ProcessResult(process.waitFor(),
                new String(stdout, StandardCharsets.UTF_8),
                new String(stderr, StandardCharsets.UTF_8));
    }

    private static String javaCommand() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private record Invocation(int status, String stdout, String stderr) {
    }

    private record ProcessResult(int status, String stdout, String stderr) {
    }

    private static final class TrackingInput extends ByteArrayInputStream {
        private boolean closed;

        private TrackingInput(String text) {
            super(text.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    private static final class TrackingOutput extends ByteArrayOutputStream {
        private boolean closed;

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
