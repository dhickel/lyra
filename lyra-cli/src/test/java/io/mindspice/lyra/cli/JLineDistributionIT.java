package io.mindspice.lyra.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Package-phase checks: shaded discovery resources and real relocated launchers. */
@Timeout(60)
final class JLineDistributionIT {
    @TempDir
    Path temp;

    @Test
    void shadedCliRetainsProviderResourcesAndEnablesNativeAccess() throws Exception {
        try (JarFile jar = new JarFile(cliJar().toFile())) {
            assertEquals("ALL-UNNAMED", jar.getManifest().getMainAttributes().getValue("Enable-Native-Access"));
            for (String resource : List.of("META-INF/jline/providers/ffm", "META-INF/jline/providers/exec",
                    "org/jline/reader/LineReader.class", "org/jline/utils/xterm.caps",
                    "org/jline/nativ/Linux/x86_64/libjlinenative.so")) {
                assertNotNull(jar.getJarEntry(resource), resource);
            }
            String providers = new String(jar.getInputStream(jar.getJarEntry(
                    "META-INF/services/org.jline.terminal.spi.TerminalProvider")).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(providers.contains("org.jline.terminal.impl.ffm.FfmTerminalProvider"));
            assertTrue(providers.contains("org.jline.terminal.impl.exec.ExecTerminalProvider"));
            assertNull(jar.getJarEntry("module-info.class"));
        }
        JLinePtyTest.runPty(temp, "vi", List.of(JLinePtyTest.javaCommand(), "-jar",
                cliJar().toString(), "repl", "--keymap", "vi"));
    }

    @Test
    void packagedJarConsoleRecognizesTheCurrentBackslashCommands() throws Exception {
        // The packaged distribution runs the real relocated console classes.
        // When the jar is stale relative to a console/command change, this
        // probe fails with the actual REPL output instead of leaving a PTY
        // child alive that never sees a recognized \quit.
        Process process = new ProcessBuilder(JLinePtyTest.javaCommand(),
                "-jar", cliJar().toString(), "repl").start();
        try {
            process.getOutputStream().write("\\help\n\\quit\n".getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "stale or hung distribution REPL");
            assertEquals(0, process.exitValue());
            assertEquals(io.mindspice.lyra.repl.PlainConsole.HELP_TEXT,
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("", new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    @Test
    void relocatedSymlinkedScriptFindsJarAndPreservesPathsAndOptions() throws Exception {
        assumeTrue(System.getProperty("os.name").equals("Linux"));
        Path distribution = temp.resolve("distribution with spaces");
        Path bin = Files.createDirectories(distribution.resolve("bin"));
        Path lib = Files.createDirectories(distribution.resolve("lib"));
        Files.copy(cliJar(), lib.resolve(cliJar().getFileName()));
        Path script = Files.copy(Path.of("src/main/scripts/lyra"), bin.resolve("lyra"));
        assertTrue(script.toFile().setExecutable(true));
        Path link = temp.resolve("lyra link");
        Files.createSymbolicLink(link, temp.relativize(script));
        Path root = Files.createDirectory(temp.resolve("source root with spaces"));
        JLinePtyTest.runPty(temp, "vi", List.of(link.toString(), "repl", root.toString(), "--keymap", "vi"));
    }

    @Test
    void normalArtifactsProducedByShadedCliStayFreeOfConsoleAndNativeAccess() throws Exception {
        Path source = temp.resolve("normal.lyra");
        Files.writeString(source, "let @pub main :Fn<Array<String>;I32> = (=> |args| 7)\n");
        Path artifact = temp.resolve("normal.jar");
        process(0, "-jar", cliJar().toString(), "compile", source.toString(), "--output", artifact.toString());
        try (JarFile jar = new JarFile(artifact.toFile())) {
            assertTrue(jar.stream().noneMatch(entry -> entry.getName().contains("jline")
                    || entry.getName().contains("lyra/cli") || entry.getName().contains("lyra/compiler")
                    || entry.getName().contains("lyra/repl")));
            assertNull(jar.getManifest().getMainAttributes().getValue("Enable-Native-Access"));
        }
        process(7, "-Xverify:all", "-jar", artifact.toString());
    }

    private void process(int expected, String... args) throws Exception {
        var command = new java.util.ArrayList<>(List.of(JLinePtyTest.javaCommand()));
        command.addAll(List.of(args));
        Path log = temp.resolve("process.log");
        Process process = new ProcessBuilder(command).directory(temp.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS));
            assertEquals(expected, process.exitValue(), Files.readString(log));
            assertEquals("", Files.readString(log));
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    private static Path cliJar() {
        return Path.of("target/lyra-cli-0.1.1.jar").toAbsolutePath();
    }
}
