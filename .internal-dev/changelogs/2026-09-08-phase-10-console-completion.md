# Phase 10 Console Completion

## Date

2026-09-08

## Git Commit

ccf6464f03171f5de5d1799298dd38c695bad577

## Change Summary

Completed the Phase 10 local, plain, JLine and attached console wiring left by the interrupted worker. The console now exposes exactly eight commands, routes load/reload/completion to the selected execution host, uses the managed owner session for local CLI execution, maps Ctrl-C to the exact active evaluation, and shares one runtime I/O environment between source entry and generated program input. Optional console paths use normal filesystem semantics while retaining strict UTF-8, regular-file and bounded history behavior. Independent validation additionally repaired editor line-framing leakage into verbatim `:type`, provider callback ordering, terminal construction cleanup, and managed-console/terminal cleanup ordering.

## Files

- `lyra-repl/src/main/java/io/mindspice/lyra/repl/ConsoleCommandParser.java`, `ConsoleSession.java`, `ConsoleCompletion.java`, `ConsoleFileRead.java`, `LocalConsoleSession.java`, `ManagedConsoleSession.java`, `PlainConsole.java`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/remote/RemoteConsoleSession.java`, `RemoteFileCompletion.java`, `RemoteFileRead.java`
- `lyra-cli/src/main/java/io/mindspice/lyra/cli/JLineConsole.java`, `LyraCli.java`
- Focused console, managed-session, CLI, attachment, completion and Linux PTY tests in `lyra-repl/src/test` and `lyra-cli/src/test`
- Validator repair coverage in `JLineConsoleTest` and the shared plain/JLine command framing and cleanup paths

## Behavioral Impact

`:reload` requires one target and executes rather than returning the removed no-target unavailable result. Local REPL startup remains empty; positional and repeatable roots configure discovery only. Rich completion uses committed binding metadata and bounded execution-host file/module lookup, including nested logical module names. Generated Unicode `std->io readLine` now has shipped-JLine PTY evidence for input/source/history ordering. Ctrl-C clears idle JLine editing or cooperatively cancels only the published active evaluation, including undecorated TTY mode.

## Specification Impact

Specification Impact: none. The implementation completes the already accepted Phase 10 behavior in `repl.md`, `backend-runtime.md`, `decisions.md` and the active REPL plan without changing those contracts.

## Risks

Native terminal behavior was executed only on Linux. Cancellation remains cooperative and blocking host I/O can delay it as specified. Completion is bounded and advisory; unreadable filesystem entries are omitted.

## Follow-up Items

None for Phase 10. Phase 11 packaging and Phase 12 run/compile activation remain outside this change.
