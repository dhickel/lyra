# Local Console Input Coordination

## Date

2026-09-06

## Git Commit

86696c7598b46185c56e00c421dca8ef2392eca1

## Change Summary

Added one explicit UTF-8 input environment for local REPL source entry and generated `std->io` reads. Plain source collection now uses the runtime decoder without read-ahead, and rich JLine sessions pause and resume a single terminal reader around program input.

## Files

- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/RuntimeIoEnvironment.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/LyraIo.java`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/SessionOptions.java`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/ConsoleInputCoordinator.java`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/PlainConsole.java`
- `lyra-cli/src/main/java/io/mindspice/lyra/cli/LyraCli.java`
- `lyra-cli/src/main/java/io/mindspice/lyra/cli/JLineConsole.java`
- focused plain-console, JLine, PTY, and CLI tests

## Behavioral Impact

Local CLI sessions bind the same caller-provided streams and UTF-8 environment to source input, output, error output, and session execution. Malformed console UTF-8 is reported as an input failure. Source history excludes commands and generated-program input. Attach keeps its local console environment outside the authenticated remote session.

## Specification Impact

Specification impact: none. The implementation fills the existing REPL/std->io stream-coordination boundary and does not change the language or remote protocol contracts.

## Validation

- Focused `PlainConsoleTest`: PASS, including shared Unicode source/program/next-source input, malformed UTF-8, history isolation, and caller-owned streams (21 tests).
- Full CLI module tests: PASS (43 tests), including `JLineConsoleTest`.
- Linux PTY JLine tests: PASS, including queued Unicode program input and next-source ownership.
- `AttachCliTest`: PASS, 5 tests.
- Full `lyra-repl` test run before the final unrelated worktree change: console tests passed; six callable-linkage tests failed outside this slice.
- A later `lyra-repl` lifecycle run was blocked by the unrelated source mismatch `ConsoleSession.java:192` calling missing `SourceOrigin.version()`.
- Full reactor validation: blocked by an unrelated pre-existing compiler syntax error at `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/SemanticFlowFacts.java:208`.

## Risks

Windows and macOS terminal behavior were not executed. The full compiler/repl reactor remains unavailable while unrelated compiler/repl work fails. Source-to-AOT session integration depends on the existing `LyraSession` load-options wiring, which is present in the current worktree but was not modified by this slice.

## Follow-up Items

Retain the explicit `LoadOptions.defaults().withIoEnvironment(options.ioEnvironment())` wiring when changing `LyraSession`; do not create a second source or program input reader in local console paths.
