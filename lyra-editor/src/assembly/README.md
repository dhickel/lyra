# Lyra Editor

This distribution contains the editor, its libraries, documentation, and a working Lyra project.

Install Java 25 for your operating system and architecture, then extract this entire directory. Keep `lib/` alongside the application JAR.

On Linux or macOS:

```sh
./lyra-editor examples/editor
```

On Windows:

```bat
lyra-editor.cmd examples\editor
```

Omit the directory argument to choose a project from the editor. These launchers use `JAVA_HOME` when set, or `java` from your PATH. The JavaFX native libraries must match the operating system and architecture on which this distribution was built.

Choose a function in the right pane and click **Run function**, or **Set entry** and use **F5**. **Debug** stops in the selected function; **F7/F8/F9** step into, step over, and continue. Enter expressions in the bottom REPL to inspect and change initialized state. **Stop** ends an owned process and enables editing after debugging.

Read [the editor guide](docs/editor.md) for editing, saving, recovery, imports, REPL commands, keyboard shortcuts, debugging limits, and runtime attachment. Follow [the example walkthrough](examples/editor/README.md) to try the development workflow.

Source and recovery files stay in your project. Opening a directory only discovers source; execution requires an explicit Run or REPL action.
