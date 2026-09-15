# Use Lyra Editor

## Prerequisites

Use Java 25 and Maven 3.9 or newer. Build the editor from the repository root:

```sh
mvn -pl lyra-editor -am package
./tools/lyra-editor.sh examples/editor
```

## Open and configure a project

Pass a directory to the launcher or choose **File → Open directory**. Opening a directory configures discovery and analysis only. It does not execute source.

Use **File → Source roots** to configure logical-module roots. Settings are stored in `.lyra/editor.properties`. Open unsaved buffers replace their disk contents for background import and type checking.

## Run a function

1. Open a `.lyra` file.
2. Select a top-level function in **Definitions**.
3. Choose **Run function** or press **F6**.
4. Supply one Lyra expression per positional parameter. A common `main` argument is `Array<String>[]`.

Run saves open buffers, checks source, starts a fresh editor-owned Java 25 child process, loads the selected file once, and invokes the selected function. The child session remains available in the bottom REPL after the function returns.

Choose **Set entry** to save a file, function, and argument-expression list. Press **F5** to run that entry. An editor entry can be any top-level function. **Build application JAR** still requires the standalone public `main :Fn<Array<String>;I32>` contract.

## Use the bottom REPL

- Enter submits a complete form.
- Shift+Enter adds a line.
- Ctrl+Enter submits REPL input. In the source editor it evaluates the selection or enclosing top-level form.
- Ctrl+Shift+Enter saves and loads the current file.
- **Interrupt** requests cooperative cancellation.
- **Stop** terminates an editor-owned child process.

The editor uses the same eight backslash commands as the local console. Program stdin has a separate field and Send/EOF controls. It is not REPL input or source history.

## Debug

Choose **Debug function** or debug the saved entry. Set breakpoints from the gutter or with Ctrl+F8. Use F7 to step into, F8 to step over, Shift+F8 to step out, and F9 to continue.

Debugging uses JDI against generated JVM bytecode. Source is read-only while the debug session is retained. The compiler currently provides no local-variable tables, so arbitrary body-local inspection is not promised. Arguments and available fields remain visible. Tail-call lowering and generated bridges can affect visible frames and stops.

A paused evaluation owns the session. Continue or finish it before another REPL evaluation.

## Attach to an external application

Read the [attachment security warning](attach-to-application.md#security-warning) first. Choose **Run → Attach to running application** and enter the loopback endpoint printed by an enabled host. Stop detaches without closing that host. Host I/O remains at the host. External REPL attachment does not also attach the JDI debugger.

## Save and recover

Files are strictly decoded UTF-8 and limited to 2 MiB. Existing BOM and CRLF conventions are retained. Saves use atomic replacement and refuse silent overwrite after an external change. Reload the disk version or explicitly confirm overwrite.

Recovery copies are written under `.lyra/recovery/`. Restoration is explicit and remains unsaved until you save. Dirty tab close, project switch, and editor exit offer Save, Discard, and Cancel.

See the [full editor guide](../../editor.md) and [runnable example](../../../examples/editor/README.md).
