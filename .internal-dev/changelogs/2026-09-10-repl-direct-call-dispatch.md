# REPL direct-call dispatch

## Date

2026-09-10

## Git Commit

4a865f666521923217a68b1f9a9ffc99656428a4

## Change Summary

Fixed REPL command/source classification so unqualified Lyra direct calls beginning with `::` are evaluated as source instead of being reported as unknown console commands.

## Files

- `lyra-repl/src/main/java/io/mindspice/lyra/repl/PlainConsole.java`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/ConsoleInputCoordinator.java`
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/PlainConsoleTest.java`
- `lyra-cli/src/main/java/io/mindspice/lyra/cli/JLineConsole.java`
- `lyra-cli/src/main/java/io/mindspice/lyra/cli/LyraHighlighter.java`
- `lyra-cli/src/test/java/io/mindspice/lyra/cli/JLineConsoleTest.java`
- `docs/repl.md`
- `.internal-dev/specifications/repl.md`

## Behavioral Impact

Only a single leading colon is treated as a console command. `::name[args]` now follows normal Lyra source evaluation, including plain and JLine consoles, while `:type ::name[args]` remains a command with source payload.

## Specification Impact

Updated `.internal-dev/specifications/repl.md` to state the single-colon command boundary. Updated `docs/repl.md` with the user-facing rule.

## Risks

The behavior of inputs beginning with a single colon is unchanged. Inputs beginning with `::` that were previously rejected as unknown commands now reach normal compiler diagnostics or evaluation, as required by the language call syntax.

## Follow-up Items

None.
