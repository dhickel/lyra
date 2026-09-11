package io.mindspice.lyra.editor;

/** A separate launcher permits JavaFX to start from a classpath distribution. */
public final class EditorLauncher {
    private EditorLauncher() { }
    public static void main(String[] args) {
        if (args.length > 0 && (args[0].equals("--help") || args[0].equals("-h"))) {
            System.out.println("Usage: lyra-editor [PROJECT_DIRECTORY]\nJava 25 is required. Opening a project never executes source.");
            return;
        }
        javafx.application.Application.launch(EditorApplication.class, args);
    }
}
