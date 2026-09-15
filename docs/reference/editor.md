# Lyra Editor

Lyra Editor is the optional JavaFX/RichTextFX workspace in `lyra-editor`. It uses compiler snapshots for editing and a disposable Java 25 child process for execution. Opening or analyzing a project never executes source.

## Start

```sh
mvn -pl lyra-editor -am package
./tools/lyra-editor.sh examples/editor
```

The workspace provides a directory tree, tabbed source editor, definition and live-binding views, REPL, problems, search, and debugger panes. See the [editor guide](../editor.md) and [walkthrough](../../examples/editor/README.md).

## Editing and analysis

Background analysis runs lexer, grammar matching, parsing, discovery, resolution, and type checking against immutable snapshots. Open unsaved imports override their disk text for analysis. A stale result is not published over a newer buffer/workspace version. Analysis never initializes modules.

Available lookup surfaces include compiler token highlighting, project/current-file definitions, checked reference navigation, name completion, project file search, find/replace, diagnostics, and source-span navigation. Completion is name/type/keyword/live-binding lookup, not refactoring or a public language-server protocol.

Files are strict UTF-8 and limited to 2 MiB. Existing BOM and CRLF conventions are retained. Saves stage a sibling file and require atomic replacement; unsupported atomic replacement is reported. External changes require explicit reload or overwrite. Dirty close/exit offers save, discard, or cancel. Recovery copies are written under `.lyra/recovery/` and restored only by explicit user action.

Project settings are stored in `.lyra/editor.properties`. They contain source roots and an optional entry file, function, and Lyra argument-expression text. Workspace-relative paths remain relative.

## Run and REPL

Run saves open buffers, checks the target, starts a fresh editor-owned child JVM, and explicitly loads the selected file once. The selected top-level function is called with ordinary direct Lyra syntax. Private top-level functions are available in that loaded session; nested functions require their lexical environment.

An editor entry is a convenience tuple of file, top-level function, and argument expressions. It does not change the executable ABI. Building a bundled application still requires `main :Fn<Array<String>;I32>`. An integer function result is displayed and does not exit the editor.

After a run, the child session remains available for persistent REPL evaluation. A new Run starts a fresh session. The editor uses the same `\help`, `\bindings`, `\type`, `\load`, `\reload`, `\reset`, `\history`, and `\quit` command meanings as other consoles. Program stdin is a separate field and is never source history. Interrupt requests cooperative cancellation. Stop terminates only an editor-owned process, including blocked code.

The GUI receives protocol-v2 snapshots and passive source metadata only. No live generated object or owner thread enters JavaFX. The child currently launches with a 768 MiB heap limit. That operational limit is not a language sandbox.

The editor can attach to an explicitly enabled application REPL. Attachment remains single-controller, loopback-only, trusted, and unauthenticated. Stop detaches without closing the external application. Host I/O remains at the host, and rejected/disconnected evaluations are not replayed automatically.

## Debugging

Editor-owned debug runs use JDI over loopback and actual generated JVM source/line information. Breakpoints, step into, step over, step out, continue, source frames, arguments, available JVM locals, and captured fields operate on the child process. Display is bounded and does not call target-side methods such as `toString`.

Source remains read-only while a debug session is retained. A paused evaluation is non-reentrant, so continue or finish it before REPL evaluation. Blank/comment-only lines have no executable breakpoint. Tail-call lowering and generated bridges can affect visible frames and stops. The compiler does not currently emit local-variable tables, so arbitrary body-local inspection is not promised. External REPL attachment does not implicitly attach JDI.

## Packaging and platform status

`mvn package` creates a platform-specific ZIP with launchers, application and dependency JARs, docs, and examples. `tools/package-editor.sh` uses Java 25 `jpackage` for a platform-specific application image whose runtime must include `bin/java`, JDI, and JDWP for child execution and debugging.

The implementation is exercised on Linux. A Linux build does not qualify Windows or macOS application images, installers, or signing. Build and validate each target platform separately. No claim is made for native installers beyond the platform-specific image/ZIP procedures verified in the editor guide.
