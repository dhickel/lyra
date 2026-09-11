# REPL Source Identity Regression Assertion Repair

## Date

2026-09-05

## Git Commit

86696c7598b46185c56e00c421dca8ef2392eca1 (dirty-worktree baseline; no commit created)

## Change Summary

Replaced the stale caller-URI workspace-key assertion in `LyraSessionTest.repeatedUrisCommitDistinctModulesAndMapLaterDiagnosticsExactlyOnce` with the first source record's synthetic path key. The existing second-record path-key assertion, distinct compiler identities, caller origins, exact diagnostic URI/UTF-16 range, and failure-publication checks remain intact. No implementation files changed.

Validation on OpenJDK 25.0.4:

- Before the edit, the focused regression reproduced the single assertion failure at line 155; the 12 selected compiler session tests passed.
- After the edit, `mvn -pl lyra-repl -am '-Dtest=SessionCompilerTest,LyraSessionTest#repeatedUrisCommitDistinctModulesAndMapLaterDiagnosticsExactlyOnce' -Dsurefire.failIfNoSpecifiedTests=false test` passed all 13 selected tests.
- `mvn -pl lyra-repl -am test` passed all 565 tests: runtime 15, compiler 504, REPL 46; zero failures, errors, or skips.
- Initial REPL-only focused selections stopped before executing the regression because the compiler POM requires tests. Adding `SessionCompilerTest` to the selection resolved the validation-command issue without build changes.

## Files

- `lyra-repl/src/test/java/io/mindspice/lyra/repl/LyraSessionTest.java`
- `.internal-dev/knowledge/repl-session-api-validation.md`
- `.internal-dev/changelogs/2026-09-05-repl-source-identity-regression.md`

## Behavioral Impact

None in production. The regression now matches the already-repaired session-owned compiler identity policy while continuing to prove caller-URI diagnostics are mapped exactly once. The validator fix and unrelated work are unchanged.

## Specification Impact

Specification Impact: none. This test-only correction preserves the existing passive caller-origin and exact source-mapping contracts in `specifications/repl.md`.

## Risks

No new implementation risk identified. Validation covers the requested REPL dependency reactor, not additional CLI, linkage, interpreter, replay, console, or remote implementation work.

## Follow-up Items

None for this repair.
