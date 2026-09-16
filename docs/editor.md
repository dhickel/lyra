# Lyra Editor

Lyra Editor is a JavaFX workspace built around the compiler and the persistent REPL. It has a project tree on the left, source tabs in the middle, definitions and live bindings on the right, and REPL, diagnostics, search and debugger tabs below. Opening a directory only configures source discovery; it does not run project code.

## Start from this repository

Use Java 25 and Maven 3.9 or newer:

```sh
mvn -pl lyra-editor -am package
./tools/lyra-editor.sh examples/editor
```

The script builds missing editor artifacts, then launches the editor. Re-run Maven after changing the editor's Java sources. You can omit the directory and use **File → Open directory**. The [example walkthrough](../examples/editor/README.md) covers running functions, persistent state, imports and debugging.

For Maven's JavaFX launcher, install the reactor dependencies first:

```sh
mvn -pl lyra-editor -am install -DskipTests
mvn -pl lyra-editor javafx:run
```

## Edit and navigate

Files open in independent tabs with undo/redo, line numbers, indentation, delimiter matching, comment toggling and compiler token highlighting. The definitions pane lists functions and bindings, including local definitions. **All files** switches it to the project index. Double-click a definition to navigate; its context menu provides evaluation, run, debug and entry-point actions.

After a short pause in typing, the editor runs Lyra's lexer, grammar matcher, parser, module discovery, resolver and type checker in the background. Open unsaved imports participate in the same check. Diagnostics appear under **Problems**, with source spans and navigation. Incomplete or invalid source receives compiler diagnostics; incomplete literals retain highlighting for the valid prefix. Type checking does not initialize modules or execute code.

**F12** uses checked reference targets when available, including parameters and imported declarations. When a file cannot be checked, it offers a definition search. **Ctrl+Space** completes known names, parameters, live bindings, keywords and types; it is a name completion menu, not an automatic refactoring engine. **Ctrl+P** opens a searchable project file list. Find/replace supports literal or regular-expression searches, case matching, previous/next matches and literal replacement text. Project search reads open buffers as well as saved Lyra files.

The project tree watches external file changes and preserves expanded directories during refresh. Hidden/generated directories are excluded by default; enable **View → Show hidden and generated files** to browse them. Project source indexing skips `.git`, `.lyra`, `target`, `build`, `node_modules` and `.idea`, does not follow directory symlinks, and is bounded to 10,000 Lyra files. A directory view shows at most 10,000 entries; project search shows at most 1,000 matches. File watching registers up to 4,096 directories; focus refresh and manual refresh remain available.

## Save and recover

Source files are UTF-8. Existing BOMs and Windows CRLF line endings are retained on save. The editor rejects binary, malformed UTF-8 and files larger than 2 MiB. Saves stage and force a sibling temporary file, then replace the destination atomically; unsupported atomic replacement is reported rather than silently weakening the save contract. An external change is never silently overwritten. Reload it, or explicitly choose to overwrite it when saving your own changes.

Unsaved buffers have a dot in their tab. Closing a dirty tab, switching projects or exiting offers Save, Discard and Cancel. Recovery copies are written approximately every eight seconds under `.lyra/recovery/`; reopening a file offers to restore a different recovery copy. Source files are not autosaved. A restored copy remains unsaved until you save it. Save and explicit Discard remove that file's recovery copy.

`.lyra/editor.properties` stores the project source roots and selected entry file/function/argument expressions. Source roots are configured through **File → Source roots**. Paths within the workspace are stored relative to the workspace, so a project can move. Theme and window size are user preferences. Recovery files contain source text; keep `.lyra/recovery/` out of version control. The project settings file may be committed if the team wants a shared run configuration.

## Run a function or program

Select a top-level function in Definitions, then click **Run function** or press **F6**. Supply Lyra expressions for its parameters; zero-argument functions need none. The editor saves open buffers, checks the source, starts a fresh child JVM, loads the file once and invokes the function through the real REPL compiler. Private top-level functions are available because the file is loaded into the session's own scope. Local functions require their enclosing lexical scope: run/debug that enclosing function or evaluate source that supplies the scope.

Click **Set entry** to persist the selected function and its arguments. **F5** runs that target. If there is no saved target, F5 can select `main` in the current file. The usual main argument expression is `Array<String>[]`; for two arguments, use `Array<String>["first", "second"]`.

Run always starts a fresh session. After the call returns, that session remains available in the bottom REPL with its initialized definitions, imported modules and state. Use REPL evaluation to work against those values, or Run again for a fresh program. The returned value is displayed; the editor process does not exit when a function returns an integer.

**Build application JAR** compiles saved source through the normal standalone compiler. A bundled executable still requires exactly `let @pub main :Fn<Array<String>;I32> = ...`. Choosing another editor entry function does not alter the executable ABI. The output uses the existing deterministic artifact writer, including its overwrite and validation behavior.

## Work in the REPL

Enter a complete form and press **Enter**. **Shift+Enter** adds a line; incomplete delimiters keep a submission multiline. **Ctrl+Enter** also submits REPL input. Up/Down at the start/end of the input navigates the last 500 entries; history is kept in memory. Program input is separate and never added to source history.

From the source editor, **Ctrl+Enter** evaluates the selection or the enclosing top-level form; **Ctrl+Shift+Enter** saves and loads the current file. Evaluation sends source with file/version/UTF-16 origin information. To establish a file's imports and definitions in a fresh REPL, explicitly load it first. Later definition evaluations use the committed session context; source is not replayed to reconstruct previous state.

The standard commands are available:

| Command | Behavior |
| --- | --- |
| `\help` | Show the command list |
| `\bindings` | Inspect committed names and types |
| `\type SOURCE` | Check a type without execution or publication |
| `\load FILE` | Ask the execution host to read and submit one file; quoted paths with spaces work. Protocol v2 does not return the loaded source to the editor, so the command/path is not added to source history |
| `\reload MODULE` | Explicitly reload a REPL-owned module or namespace alias |
| `\reset` | Reset the session workspace and clear editor source history after success |
| `\history` | Show the in-memory input history |
| `\quit` | Stop the editor-owned process or detach from an external application |

Function syntax such as `::greet["Lyra"]` is evaluated as Lyra source. **Interrupt** cooperatively cancels the current evaluation. **Stop** terminates an editor-owned process, including a blocked program, while keeping editor buffers. A separate stdin field and Send/EOF controls serve `std->io` program input. Output is drained continuously with bounded pending/display buffers. The child JVM currently has a 768 MiB maximum heap; compiler/session limits also apply. See [REPL contracts and limits](repl.md).

**Run → Attach to running application** connects to the loopback endpoint printed by `lyra run --repl` or an enabled Java host. It uses the existing protocol, including its single-controller rule, revisions and cancellation semantics. Close another controller before attaching. Stop detaches and leaves that host alive. Attached application I/O remains with the host; this protocol does not forward its stdin/stdout. The UI does not automatically replay a rejected or disconnected evaluation. An explicitly enabled listener is a trusted, unauthenticated local development interface, as documented in [the REPL guide](repl.md).

## Debug generated Lyra code

Choose **Debug** for a selected function, or **Ctrl+F5** for the entry point. The editor launches a JVM debugger connection on loopback, compiles ordinary Lyra bytecode with its existing source/line metadata, and pauses at the selected function. Inferred function aliases pause at the next generated function invocation.

Click a source gutter or press **Ctrl+F8** to toggle a breakpoint. Breakpoints follow line insertions/removals while editing and bind to executable JVM line locations; a blank/comment-only line has no executable breakpoint. The debugger shows source frames, arguments, available JVM locals and captured object fields without invoking target-side `toString` methods. Argument names are recovered from the checked function when available. The compiler currently does not emit local-variable tables, so arbitrary body-local values cannot be inspected; arguments and fields remain visible. Tail-call elimination and synthetic bytecode can affect the number of visible stops and frames.

| Key | Action |
| --- | --- |
| F7 | Step into |
| F8 | Step over |
| Shift+F8 | Step out |
| F9 | Continue |
| Shift+F5 | Stop process |

Running source is read-only throughout a debug session so source lines cannot drift from the bytecode. Stop debugging to edit. A paused evaluation owns the session; resume or finish it before issuing another REPL evaluation. JDI debugging is provided for editor-owned processes; attaching an existing REPL does not attach a JVM debugger.

## Distribute the editor

`mvn package` creates `lyra-editor/target/lyra-editor-0.1.1-distribution.zip` with the application JAR, runtime libraries, shell/Windows launchers, documentation and examples. Extract it, then launch `lyra-editor` or `lyra-editor.cmd` with Java 25 installed. JavaFX libraries are selected for the build platform; build a distribution on each target OS/architecture. The existing compiler/runtime/CLI distributions do not acquire JavaFX dependencies.

To produce a native application image containing its Java runtime:

```sh
./tools/package-editor.sh
```

On Linux the launcher is `lyra-editor/target/native/LyraEditor/bin/LyraEditor`. Supply a different destination as the script's first argument if an image already exists. The bundled runtime retains the `java` executable and debugger modules needed for child processes. Native images are platform-specific; OS installers/signing must be produced and validated on their target platform. See the [JDK packaging documentation](https://docs.oracle.com/en/java/javase/25/docs/specs/man/jpackage.html).

Some distribution-managed JDKs omit `jmods` and modify runtime configuration files, which prevents `jlink` from deriving a smaller image. Package with a pristine Java 25 JDK, or explicitly bundle an existing Java 25 runtime that contains `bin/java`, the desktop modules, JDI and JDWP:

```sh
LYRA_EDITOR_RUNTIME_IMAGE=/path/to/java-25 ./tools/package-editor.sh
```

This uses jpackage's supported `--runtime-image` input. A full JDK is a suitable, larger runtime image. The packager resolves filesystem links while staging it, so distribution-managed configuration and time-zone files are included in the application.

## Validate changes

```sh
mvn test
mvn -pl lyra-editor -am verify -Dlyra.editor.uiTests=true
./tools/phase24-release-audit.sh
```

The explicit UI tests need a graphical display (an Xvfb display is suitable on Linux). They drive the visible controls through opening, highlighting, editing, saving, running, REPL evaluation, entry selection, stepping and stopping a paused process, and write `lyra-editor/target/editor-smoke.png`. Ordinary tests cover compiler diagnostics, unsaved import overlays, source navigation, atomic/conflicting saves, rename collisions, recovery/settings, real subprocess execution, input, cancellation, attachment, imported-module breakpoints and JDI stepping. The verify phase tests the packaged classpath and debugger worker. Phase 24 remains the backend release gate; the graphical editor check is an additional release requirement.

The implementation is exercised on Linux with Java 25. Cross-platform desktop/native installer validation is still required before distributing releases for Windows or macOS. This editor does not add language features, an interpreter, language server, arbitrary refactorings, or a sandbox. It uses [OpenJFX](https://openjfx.io/) and [RichTextFX](https://github.com/FXMisc/RichTextFX); dependency JARs retain their respective licensing metadata.
