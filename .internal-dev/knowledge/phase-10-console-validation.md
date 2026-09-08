# Phase 10 Console Validation

## Topic

Validation patterns and implementation gotchas for Lyra plain/JLine/attached consoles, managed cancellation, execution-host completion and shared program input.

## Source References

- `.internal-dev/specifications/repl.md`
- `.internal-dev/plans/20260906-221525-complete-lyra-repl-with-trusted-module-linking-and-local/plan.md` Phase 10 and V02/V13/V16/V17
- `ManagedConsoleSession`, `PlainConsole`, `JLineConsole`, `LyraCli`
- `RemoteFileCompletion`, `RemoteFileRead`, `ConsoleInputCoordinator`
- `ManagedConsoleSessionTest`, `PlainConsoleTest`, `JLineConsoleTest`, `JLinePtyTest`, `AttachCliTest`, `RemoteFileModuleTest`

## Key Takeaways

- CLI-module tests run alone can load stale `lyra-repl` classes from the local Maven repository and produce misleading `NoClassDefFoundError`/`NoSuchMethodError` failures. Install the current reactor dependencies first or run the full reactor.
- JLine and generated `readLine` must consume one terminal reader. Pause JLine after accepted source, let `programInput` resume that reader, and never add a competing input thread for Ctrl-C.
- Active cancellation must use the exact published `EvaluationId`; reload busy/closed result construction must preserve the caller identity too.
- File/module completion runs on the selected execution host. Logical module candidates use `->` even though discovery maps them to `/` paths. JLine must replace the complete command argument so nested module prefixes are not duplicated.
- Removing optional-console symlink policy applies consistently to load, history and completion. Keep regular-file/strict-UTF-8 diagnostics and bounded lookup/write behavior; symlinks follow normal filesystem semantics.
- PTY evidence must execute compiled Lyra `std->io readLine`, not merely call `RuntimeIoEnvironment.readLine` from Java. Verify Unicode program input, following source, source-only history, terminal restoration and open caller streams.
- Editor adapters may return a cooked line terminator even when the terminal provider already exposed one. Normalize that framing before command parsing and remove the console framing newline so `:type` receives exactly the submitted Lyra source, including trailing spaces.
- Local rich-console teardown must attempt both managed-owner and terminal cleanup independently. A failed terminal close must not strand the managed owner, and cleanup failures must remain suppressed when an earlier console operation already failed.

## Project Relevance

Use these checks for later attachment/bootstrap integration and release auditing so console regressions are not hidden by mocked input, client-side path access, stale Maven artifacts or direct runtime-I/O probes.

## Open Questions

Native macOS and Windows terminal behavior remains unexecuted and is N/A until those platforms are available.
