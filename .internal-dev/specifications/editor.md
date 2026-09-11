# Lyra Development Editor

## Status and ownership

Living contract for the owner-requested JavaFX development environment. The editor owns project presentation, source buffers, file safety, run configurations, and client/debugger lifecycle. `language-core.md`, `backend-runtime.md`, and `repl.md` retain ownership of language semantics, bytecode, live storage, module identity and owner confinement. Opening a project or checking source never executes it.

## Workspace and compiler tooling

`lyra-editor` is a separate classpath Maven module depending on `lyra-repl`, OpenJFX controls and RichTextFX. No existing compiler/runtime/REPL/CLI artifact depends on JavaFX. The layout is a directory tree at left, tabbed source in the center, definitions/live bindings at right, and REPL/problems/search/debugger below. Project-wide and current-file definition views are supported.

Highlighting consumes the compiler's lexical tokens and trivia. Definitions consume immutable parser AST nodes; successful type checking supplies inferred callable contracts and reference navigation. Background checks use module discovery, resolution and type checking against immutable snapshots of all open buffers and configured filesystem roots. Unsaved imports replace their disk contents for that analysis; discovery still owns ambiguity and identity checks. Stale analysis must not publish over a newer buffer/workspace version. Tooling never evaluates initializers.

Files are bounded, strictly decoded UTF-8. Source spans and edit/navigation offsets are UTF-16. Existing BOM/newline conventions are retained on save. Sibling staging and atomic replacement preserve the old file on write failure. Externally changed source must be reloaded or explicitly overwritten. Dirty tabs and workspace exit require the editor's save/discard/cancel interaction. Recovery copies are separate from source and restored only by an explicit user action. Project-local settings live in `.lyra/editor.properties`; recovery copies live in `.lyra/recovery/`.

## Execution and REPL

Run saves open buffers, validates the selected source and creates a fresh editor-owned JVM. That process owns a `LyraSession`, a controller and the existing protocol-v2 server. Its source roots configure discovery only. An explicit file load initializes the selected source once; the selected top-level function is invoked through ordinary Lyra call syntax and compiler-certified session linkage. Further REPL submissions reuse initialized storage. Private top-level functions are in session scope; nested functions still require their enclosing lexical environment.

An editor entry target is a file, top-level function name and Lyra argument-expression text. It is an editor convenience and does not replace the standalone executable `main :Fn<Array<String>;I32>` ABI. Building an executable JAR uses the ordinary compiler/artifact writer and that exact ABI. Run does not exit the editor when an integer is returned.

The GUI exchanges only the existing bounded protocol results and passive source metadata. No live generated value or owner thread is moved into JavaFX. Source acquisition, analysis, file traversal, execution, control and output delivery do not share a blocking UI execution path. Program stdin is separate from REPL input/history. Output/history and filesystem traversal are bounded. Cooperative cancellation is identity-specific; Stop terminates only the owned child process. Parent death retires orphaned children.

The editor may explicitly attach to an existing enabled loopback REPL. The protocol remains unauthenticated and single-controller, with no new remote operations or transport ABI. Stop detaches without closing the externally owned application. Its I/O remains with that host. Requests are never replayed automatically after failure, disconnect, or revision conflict.

## Debugging

Editor-owned debug processes use JDI over an explicit loopback connection and the compiler's existing JVM source/line attributes. Breakpoints and step-into/over/out operate on actual JVM events. Source identity for loaded submissions is mapped from the actual evaluation request ID to the source file; imported modules retain normal source resolution. Function aliases may stop at the next generated callable invocation. Debugger inspection reads arguments, available local-variable metadata and fields, with bounded display and without target method invocation.

Source is read-only while a debug session is retained, including after a function returns. Stop unlocks editing. Paused session evaluation is not reentrant; REPL evaluation requires continuation or completion. Blank lines have no executable breakpoint. Direct self-tail calls and generated bridges retain their existing bytecode behavior. The current compiler provides no local-variable tables; arbitrary body-local inspection is therefore not promised. External REPL attachment does not implicitly open or attach a JVM debugger.

## Packaging and release validation

The module packages a platform-specific ZIP containing launchers, application JAR, dependency JARs, docs and examples. Native application images are produced with Java 25 jpackage on the target platform. Their runtime must retain `bin/java`, JDI and the JDWP agent so the editor can launch/debug its worker. Desktop packaging does not alter generated Lyra artifact contents.

Required evidence is source-to-execution integration, cancellation/input tests, real source-mapped JDI stops and stepping, file conflict/recovery tests, compiler/unsaved-import diagnostics, a graphical control-driven edit/save/run/REPL test, and a packaged-classpath worker test. Run `mvn test`, the explicit `-Dlyra.editor.uiTests=true` verify pass on a display, and the Phase 24 backend release audit. Cross-platform installer support requires target-platform validation; Linux results do not certify macOS/Windows installers.
