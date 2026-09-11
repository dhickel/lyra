# JLine Console Adapter

## Date

2026-09-05

## Git Commit

86696c7598b46185c56e00c421dca8ef2392eca1

## Change Summary

Added the CLI-only JLine 4.0.0 console adapter over the shared plain console/session behavior. Interactive TTY sessions now provide multiline prompts, lexical continuation, bracketed-paste submission, indentation, syntax highlighting, delimiter matching, Emacs/vi keymap selection, in-memory searchable history, completion for essential commands and known types, terminal resize/redraw handling, and cleanup. Non-TTY and `--plain` sessions retain the dependency-free console.

## Files

- `pom.xml`
- `lyra-cli/pom.xml`
- `lyra-cli/src/main/java/io/mindspice/lyra/cli/LyraCli.java`
- `lyra-cli/src/main/java/io/mindspice/lyra/cli/JLineConsole.java`
- `lyra-cli/src/main/java/io/mindspice/lyra/cli/LyraHighlighter.java`
- `lyra-cli/src/main/scripts/lyra`
- `lyra-cli/src/main/scripts/lyra.bat`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/PlainConsole.java`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/LexicalCompleteness.java`
- related CLI/JLine tests and test resources

## Behavioral Impact

Default interactive `lyra repl` attempts rich JLine mode and falls back to plain mode when no usable TTY/provider is available. JLine is scoped to the CLI and is absent from `lyra-runtime` and ordinary generated artifacts. History remains source-only, bounded, and non-replaying. Unsupported persistent-linkage features remain explicitly reported.

## Specification Impact

Implements the rich-console portion of the REPL contract and the JLine dependency-isolation boundary. Native macOS/Windows behavior remains unvalidated.

## Validation

- JLine/REPL/CLI validator: PASS.
- `mvn clean verify`: PASS, 511 compiler, 70 REPL, 37 CLI, and 3 JLine distribution tests.
- `git diff --check`: PASS.

## Risks

JLine 4.0.0 POSIX provider behavior relies on the available native terminal tooling; Linux validation passed, while macOS and Windows were not executed. Functional persistent values, `:type`, and `:reload` remain unavailable.

## Follow-up Items

Complete authenticated direct-bytecode session linkage and live value snapshots before enabling cross-submission state. Add application debug packaging/attachment only after that linker exists.
