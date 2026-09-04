package io.mindspice.lyra.cli;

import io.mindspice.lyra.runtime.LyraRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase-22 subprocess sealing for the CLI boundary. */
final class Phase22CliConformanceTest {
    private static final String MAIN =
            "let @pub main :Fn<Array<String>;I32> = (=> |args| args:.length)\n";

    @TempDir
    Path temp;

    @Test
    void thinJarRunsWithAnExternalRuntimeAndJvmVerification() throws Exception {
        Path source = write("main.lyra", MAIN);
        Path thin = temp.resolve("main-thin.jar");
        Invocation compile = invoke("compile", source.toString(), "--format", "thin-jar",
                "--output", thin.toString());
        assertEquals(0, compile.status());
        String facade = facadeName(thin);
        String runtime = Path.of(LyraRuntime.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI()).toString();
        ProcessResult result = process(javaCommand(), "-Xverify:all", "-cp",
                thin + java.io.File.pathSeparator + runtime + java.io.File.pathSeparator
                        + System.getProperty("java.class.path"),
                ThinConsumer.class.getName(), facade);
        assertEquals(0, result.status(), result.output());
        assertEquals("", result.output());
    }

    @Test
    void runMapsRuntimeFailuresAndDeferredSyntaxToSourceFailures() {
        Path arithmetic = write("arithmetic.lyra",
                "let zero :I32 = 0 "
                        + "let @pub main :Fn<Array<String>;I32> = "
                        + "(=> |args| I32[(/ args:.length zero)])\n");        Invocation runtime = invoke("run", arithmetic.toString());
        assertEquals(1, runtime.status());
        assertTrue(runtime.stderr().contains("LYR-ARITH"));
        assertTrue(runtime.stderr().contains("arithmetic.lyra"));
        assertTrue(runtime.stderr().contains("^"));
        assertTrue(runtime.stderr().contains("main"));

        Path deferred = write("deferred.lyra",
                "let @pub main :Fn<Array<String>;I32> = (=> |args| (try 1))\n");
        Invocation syntax = invoke("run", deferred.toString());
        assertEquals(1, syntax.status());
        assertTrue(syntax.stderr().contains("LYC-"));
        assertTrue(syntax.stderr().contains("deferred.lyra"));
        assertFalse(syntax.stderr().contains("internal CLI failure"));
    }

    private Invocation invoke(String... arguments) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int status = LyraCli.execute(arguments, new ByteArrayInputStream(new byte[0]), output, error);
        return new Invocation(status, output.toString(StandardCharsets.UTF_8),
                error.toString(StandardCharsets.UTF_8));
    }

    private Path write(String name, String source) {
        try {
            Path path = temp.resolve(name);
            Files.writeString(path, source, StandardCharsets.UTF_8);
            return path;
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static String facadeName(Path jar) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            return file.stream()
                    .map(entry -> entry.getName().replace('/', '.'))
                    .filter(name -> name.endsWith(".class"))
                    .map(name -> name.substring(0, name.length() - ".class".length()))
                    .filter(name -> name.contains(".$lyra$facade$"))
                    .findFirst().orElseThrow();
        }
    }

    private static ProcessResult process(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        return new ProcessResult(process.waitFor(), new String(output, StandardCharsets.UTF_8));
    }

    private static String javaCommand() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private record Invocation(int status, String stdout, String stderr) {
    }

    private record ProcessResult(int status, String output) {
    }

    /** External ordinary-Java consumer for the thin-jar verification test. */
    public static final class ThinConsumer {
        public static void main(String[] args) throws Exception {
            Class<?> facade = Class.forName(args[0]);
            Object module = facade.getMethod("$lyra$create").invoke(null);
            try {
                int result = (Integer) facade.getMethod("main", String[].class)
                        .invoke(module, (Object) new String[]{"left", "right"});
                if (result != 2) {
                    throw new AssertionError("main returned " + result);
                }
            } finally {
                facade.getMethod("close").invoke(module);
            }
        }
    }
}
