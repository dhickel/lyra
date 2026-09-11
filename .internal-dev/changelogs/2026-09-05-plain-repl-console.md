# Plain REPL Console

## Date

2026-09-05

## Git Commit

86696c7598b46185c56e00c421dca8ef2392eca1

## Change Summary

Added a dependency-free plain/non-TTY REPL console and `lyra repl [ROOT]` CLI entry point. The console supports the essential command set, top-level command recognition, quoted command arguments, multiline lexical completeness, UTF-8 file loading, in-memory history, metadata-only bindings, reset, and truthful unsupported responses for live type/reload operations.

## Files

- `lyra-repl/src/main/java/io/mindspice/lyra/repl/PlainConsole.java`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/ConsoleCommandParser.java`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/LexicalCompleteness.java`
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/PlainConsoleTest.java`
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/ConsoleParsingTest.java`
- `lyra-cli/src/main/java/io/mindspice/lyra/cli/LyraCli.java`
- `lyra-cli/src/test/java/io/mindspice/lyra/cli/Phase21CliTest.java`
- `lyra-cli/pom.xml`

## Behavioral Impact

Piped and plain console sessions now accept source submissions and continue through recoverable evaluation failures. History never replays or records program input. File loads require strict UTF-8 and non-symbolic regular files. Live `:type`, `:reload`, and persistent live values remain explicitly unavailable.

## Specification Impact

Implements the plain-console subset of the REPL console contract and preserves the direct-bytecode/session boundary. Rich JLine editing and live linkage remain follow-up work.

## Validation

- Plain-console validation: PASS.
- `mvn test -Dsurefire.failIfNoTests=false`: PASS, 605 tests.
- `git diff --check`: PASS.

## Risks

The console currently cannot evaluate functional `:type` or `:reload` because persistent session linkage is not implemented. Rich TTY editing, completion, highlighting, and history-file persistence are not present.

## Follow-up Items

Implement authenticated typed session linkage and live value snapshots before enabling persistent cross-submission behavior. Add JLine as a CLI-only adapter after the plain console contract remains stable.
