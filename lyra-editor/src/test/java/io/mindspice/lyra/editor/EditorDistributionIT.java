package io.mindspice.lyra.editor;

import io.mindspice.lyra.repl.ConsoleSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class EditorDistributionIT {
    @TempDir Path project;
    @Test @Timeout(40) void packagedJarStartsACompilerReplAndDebugWorkerUsingOnlyDistributionLibraries() throws Exception {
        Path jar = Path.of("target/lyra-editor-0.1.1.jar").toAbsolutePath();
        assertTrue(Files.isRegularFile(jar));
        Path log = project.resolve("distribution.log");
        String javaCommand = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        Process process = new ProcessBuilder(javaCommand, "--enable-preview", "--add-modules=jdk.jdi", "-cp",
                jar + java.io.File.pathSeparator + Path.of("target/test-classes").toAbsolutePath(), Probe.class.getName(), project.toString())
                .directory(project.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "packaged worker timed out");
            assertEquals(0, process.exitValue(), Files.readString(log));
            assertTrue(Files.readString(log).contains("PACKAGED_REPL=42"), Files.readString(log));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
    /** Also used to verify the Java runtime embedded in a native application image. */
    public static final class Probe {
        public static void main(String[] args) throws Exception {
            Path root = Path.of(args[0]).toAbsolutePath();
            Files.createDirectories(root);
            Path source = root.resolve("main.lyra");
            Files.writeString(source, "let answer :Fn<;I32> = (=> || 42)");
            try (var runtime = EditorRuntime.start(WorkspaceSettings.open(root), true, System.out::print, pause -> {}, System.out::println)) {
                var loaded = runtime.load(source);
                if (loaded.status() != ConsoleSession.EvaluationStatus.SUCCESS) throw new IllegalStateException(loaded.toString());
                var result = runtime.evaluate("::answer[]");
                if (result.status() != ConsoleSession.EvaluationStatus.SUCCESS) throw new IllegalStateException(result.toString());
                System.out.println("PACKAGED_REPL=" + result.value().orElseThrow().display());
            }
            if (args.length > 1 && args[1].equals("--ui")) gui(root, source);
        }
        private static void gui(Path root, Path source) throws Exception {
            WorkspaceSettings.open(root).withEntry(new WorkspaceSettings.RunTarget(source, "answer", "")).save();
            var ready = new java.util.concurrent.CompletableFuture<Void>();
            javafx.application.Platform.startup(() -> { javafx.application.Platform.setImplicitExit(false); ready.complete(null); });
            ready.get(10, TimeUnit.SECONDS);
            var stage = fx(javafx.stage.Stage::new);
            EditorWindow window = fx(() -> { var value = new EditorWindow(stage); stage.show(); value.openWorkspace(root); return value; });
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (!fx(() -> ((javafx.scene.control.ListView<?>) stage.getScene().lookup("#function-list")).getItems().size() == 1)) {
                    if (System.nanoTime() > deadline) throw new IllegalStateException("Packaged GUI did not check the source");
                    Thread.sleep(50);
                }
                fx(() -> { ((javafx.scene.control.Button) stage.getScene().lookup("#run-entry")).fire(); return null; });
                while (!fx(() -> ((javafx.scene.control.TextArea) stage.getScene().lookup("#repl-output")).getText().contains("⇒ 42"))) {
                    if (System.nanoTime() > deadline) throw new IllegalStateException("Packaged GUI did not run the source");
                    Thread.sleep(50);
                }
                System.out.println("PACKAGED_GUI=42");
            } finally {
                fx(() -> { window.dispose(); stage.hide(); return null; }); javafx.application.Platform.exit();
            }
        }
        private static <T> T fx(java.util.concurrent.Callable<T> action) throws Exception {
            var result = new java.util.concurrent.CompletableFuture<T>();
            javafx.application.Platform.runLater(() -> { try { result.complete(action.call()); } catch (Throwable failure) { result.completeExceptionally(failure); } });
            return result.get(10, TimeUnit.SECONDS);
        }
    }
}
