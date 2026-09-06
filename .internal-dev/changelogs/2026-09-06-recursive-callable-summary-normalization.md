# Recursive Callable Summary Normalization

## Date

2026-09-06

## Git Commit

86696c7598b46185c56e00c421dca8ef2392eca1

Baseline HEAD only. No commit was made; unrelated dirty and untracked work was preserved.

## Change Summary

Fixed recursive callable-summary fixed-point growth for captured-cell writes. Recursive transfers now retain one source-operation occurrence and join later substituted value formulas instead of appending the same recursive trace until the finite write domain overflows. Ownership requirements use the same bounded normalization.

## Files

- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/CallableSummarySolver.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/CapturedCellWrite.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/OwnershipRequirement.java`
- `lyra-compiler/src/test/java/CallableSummaryTest.java`
- `.internal-dev/knowledge/callable-summary-recursion.md`

## Behavioral Impact

The issue #2 recursive captured scalar-cell reproducer is accepted through the typed compiler and session compiler APIs. Captured-cell identity, route, source order, value alternatives and ownership evidence remain represented. Distinct source operations are not collapsed, and deliberately undersized write/ownership domains still return `DOMAIN_LIMIT`.

## Specification Impact

None. The change implements the existing finite-summary, ordered-effect, ownership and immutable symbolic-flow contracts; it does not widen callable admission or alter runtime/session ownership policy.

## Risks

The full worktree contains concurrent session/runtime edits. Focused callable-summary and ownership tests pass, as does the recursive REPL API regression, but the latest broad `mvn test` run reports 543 tests with 5 failures and 6 errors in session storage linkage, session callable guard/publication expectations, attempted-flow validation, sealing identity, and imported computed-call flow. Those files were not edited for this repair.

## Follow-up Items

Re-run the full suite after the concurrent session/runtime work is reconciled. Keep the recursive write and ownership limit regressions when finalizing the larger session change.
