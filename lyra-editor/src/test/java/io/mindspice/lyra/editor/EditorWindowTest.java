package io.mindspice.lyra.editor;

import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.fxmisc.richtext.CodeArea;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "lyra.editor.uiTests", matches = "true")
@Timeout(60)
class EditorWindowTest {
    @TempDir Path root;
    @BeforeAll static void toolkit() throws Exception {
        CompletableFuture<Void> ready = new CompletableFuture<>();
        Platform.startup(() -> { Platform.setImplicitExit(false); ready.complete(null); });
        ready.get(10, TimeUnit.SECONDS);
    }
    @Test void opensProjectHighlightsEditsSavesRunsAndEvaluatesThroughVisibleControls() throws Exception {
        Path file = root.resolve("main.lyra");
        Files.writeString(file, "// A small interactive program\nlet answer :Fn<;I32> = (=> || 41)\n"
                + "let @pub main :Fn<Array<String>;I32> = (=> |args| { ::answer[] })\n");
        Files.writeString(root.resolve("loaded-ui-history-source.lyra"),
                "let loadedUiHistory :I32 = 7I32\n");
        WorkspaceSettings.open(root).withEntry(new WorkspaceSettings.RunTarget(file, "main", "Array<String>[]")).save();
        Stage stage = fx(Stage::new);
        EditorWindow window = fx(() -> { var value = new EditorWindow(stage); stage.show(); value.openWorkspace(root); return value; });
        try {
            await(() -> fxUnchecked(() -> ((ListView<?>) stage.getScene().lookup("#function-list")).getItems().size() == 2));
            fx(() -> {
                assertNotNull(stage.getScene().lookup("#project-files")); assertNotNull(stage.getScene().lookup("#repl-input"));
                CodeArea area = (CodeArea) stage.getScene().lookup("#source-editor");
                assertTrue(area.getStyleOfChar(0).contains("syntax-comment"));
                String original = area.getText();
                area.insertText(0, "\n"); area.moveTo(0);
                area.fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED, "", "",
                        javafx.scene.input.KeyCode.ENTER, false, false, false, false));
                assertTrue(area.getText().startsWith("\n\n"), "Enter at the start of an empty first line must insert a newline");
                area.replaceText(original.replace("41", "42"));
                ((Button) stage.getScene().lookup("#run-entry")).fire();
                return null;
            });
            await(() -> fxUnchecked(() -> ((TextArea) stage.getScene().lookup("#repl-output")).getText().contains("⇒ 42")
                    && !((Button) stage.getScene().lookup("#evaluate-repl")).isDisabled()));
            assertTrue(Files.readString(file).contains("42"));
            fx(() -> {
                ((TextArea) stage.getScene().lookup("#repl-input")).setText("::answer[]");
                ((Button) stage.getScene().lookup("#evaluate-repl")).fire(); return null;
            });
            await(() -> fxUnchecked(() -> ((TextArea) stage.getScene().lookup("#repl-output")).getText().split("⇒ 42", -1).length >= 3));
            fx(() -> {
                TextArea input = (TextArea) stage.getScene().lookup("#repl-input");
                input.setText("let uiHistoryMarker = 1");
                ((Button) stage.getScene().lookup("#evaluate-repl")).fire();
                return null;
            });
            await(() -> fxUnchecked(() -> ((Label) stage.getScene().lookup("#status-label"))
                    .getText().equals("Ready · REPL revision Evaluate")));
            fx(() -> {
                TextArea input = (TextArea) stage.getScene().lookup("#repl-input");
                input.setText("\\load loaded-ui-history-source.lyra");
                ((Button) stage.getScene().lookup("#evaluate-repl")).fire();
                return null;
            });
            await(() -> fxUnchecked(() -> ((Label) stage.getScene().lookup("#status-label"))
                    .getText().equals("Ready · REPL revision Load file")));
            fx(() -> {
                TextArea input = (TextArea) stage.getScene().lookup("#repl-input");
                input.clear(); input.positionCaret(0);
                input.fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED, "", "",
                        javafx.scene.input.KeyCode.UP, false, false, false, false));
                assertEquals("let uiHistoryMarker = 1", input.getText(),
                        "a remote load command/path must not replace source history");
                input.setText("\\reset");
                ((Button) stage.getScene().lookup("#evaluate-repl")).fire();
                return null;
            });
            await(() -> fxUnchecked(() -> ((Label) stage.getScene().lookup("#status-label"))
                    .getText().equals("Ready · REPL revision Reset")));
            fx(() -> {
                TextArea input = (TextArea) stage.getScene().lookup("#repl-input");
                input.clear(); input.positionCaret(0);
                input.fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED, "", "",
                        javafx.scene.input.KeyCode.UP, false, false, false, false));
                assertEquals("", input.getText(), "successful reset must clear source history");
                var image = stage.getScene().snapshot(null);
                var png = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < png.getHeight(); y++) for (int x = 0; x < png.getWidth(); x++) png.setRGB(x, y, image.getPixelReader().getArgb(x, y));
                Path target = Path.of("target/editor-smoke.png"); Files.createDirectories(target.getParent()); ImageIO.write(png, "png", target.toFile());
                return null;
            });
        } finally { fx(() -> {
            Files.writeString(Path.of("target/editor-ui-transcript.txt"), ((TextArea) stage.getScene().lookup("#repl-output")).getText());
            window.dispose(); stage.hide(); return null;
        }); }
    }
    @Test void selectsEntryDebugsAndStepsFunctionThenStopsItsOwnedProcess() throws Exception {
        Path file = root.resolve("main.lyra");
        Files.writeString(file, "let answer :Fn<;I32> = (=> || {\n    let partial :I32 = 40\n    (+ partial 2)\n})\n");
        var previousProcesses = ProcessHandle.current().descendants().map(ProcessHandle::pid).collect(java.util.stream.Collectors.toSet());
        Stage stage = fx(Stage::new);
        EditorWindow window = fx(() -> { var value = new EditorWindow(stage); stage.show(); value.openWorkspace(root); return value; });
        try {
            await(() -> fxUnchecked(() -> ((ListView<?>) stage.getScene().lookup("#function-list")).getItems().size() == 2));
            fx(() -> {
                ((ListView<?>) stage.getScene().lookup("#function-list")).getSelectionModel().selectFirst();
                ((Button) stage.getScene().lookup("#set-entry")).fire();
                ((Button) stage.getScene().lookup("#debug-function")).fire();
                return null;
            });
            await(() -> fxUnchecked(() -> !((Button) stage.getScene().lookup("#step-over")).isDisabled()));
            assertEquals("answer", WorkspaceSettings.open(root).entry().orElseThrow().function());
            fx(() -> {
                CodeArea area = (CodeArea) stage.getScene().lookup("#source-editor");
                assertFalse(area.isEditable());
                var frames = (ListView<?>) stage.getScene().lookup("#debug-frames");
                assertEquals(2, ((Debugger.Frame) frames.getItems().getFirst()).line());
                ((Button) stage.getScene().lookup("#step-over")).fire();
                return null;
            });
            await(() -> fxUnchecked(() -> {
                var frames = (ListView<?>) stage.getScene().lookup("#debug-frames");
                return !((Button) stage.getScene().lookup("#step-over")).isDisabled()
                        && !frames.getItems().isEmpty() && ((Debugger.Frame) frames.getItems().getFirst()).line() == 3;
            }));
            fx(() -> {
                ((Button) stage.getScene().lookup("#stop-process")).fire();
                assertTrue(((CodeArea) stage.getScene().lookup("#source-editor")).isEditable());
                return null;
            });
            await(() -> ProcessHandle.current().descendants().filter(process -> !previousProcesses.contains(process.pid()))
                    .noneMatch(process -> process.info().commandLine().orElse("").contains(EditorWorker.class.getName())));
        } finally { fx(() -> {
            Files.writeString(Path.of("target/editor-debug-ui-transcript.txt"), ((TextArea) stage.getScene().lookup("#repl-output")).getText());
            window.dispose(); stage.hide(); return null;
        }); }
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(50);
        assertTrue(condition.getAsBoolean(), "UI operation did not finish");
    }
    private static <T> T fx(Callable<T> action) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> { try { result.complete(action.call()); } catch (Throwable failure) { result.completeExceptionally(failure); } });
        return result.get(10, TimeUnit.SECONDS);
    }
    private static <T> T fxUnchecked(Callable<T> action) {
        try { return fx(action); } catch (Exception failure) { throw new AssertionError(failure); }
    }
}
