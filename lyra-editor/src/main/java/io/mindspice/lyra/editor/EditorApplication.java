package io.mindspice.lyra.editor;

import javafx.application.Application;
import javafx.stage.Stage;
import java.nio.file.Path;

public final class EditorApplication extends Application {
    private EditorWindow window;
    @Override public void start(Stage stage) {
        window = new EditorWindow(stage);
        stage.show();
        if (!getParameters().getRaw().isEmpty()) window.openWorkspace(Path.of(getParameters().getRaw().getFirst()));
    }
    @Override public void stop() { if (window != null) window.dispose(); }
}
