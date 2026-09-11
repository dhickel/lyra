# WIP — Nominal identity and accepted struct/class contracts

## Date

2026-09-11

## Git Commit

`7b1bc1e3aac32547d0679a5f5b66d2ecbafe3a69` (baseline before this checkpoint).

## Change Summary

Recorded the accepted mutable struct/class contract, including same-name constructors,
replaceable method slots, snapshot method selection, mutation through immutable
receivers and lexical privacy for external replacement lambdas. Added the standalone
compiler `NominalTypeId` foundation and independent deterministic/seeded tests.
Created the active four-phase implementation plan; the requested feature is unfinished.

## Files

- Compiler identity/NominalTypeId.java and test/NominalTypeIdTest.java.
- language-core, backend-runtime, repl, decisions and deferred-features specifications.
- plans/nominal-types/{plan,phase-01-syntax-and-identities,phase-02-semantics-and-flow,
  phase-03-jvm-and-artifacts,phase-04-sessions-and-validation}.md.
- knowledge/nominal-types.md and docs/language-testing.md.
- reviews/2026-09-11-nominal-identity-checkpoint-review.md.

## Behavioral Impact

Only a standalone immutable identity utility is added. It includes stable module
identity, normalized revision, capitalized name and same-name declaration occurrence;
it excludes field shape, source offsets, import aliases and runtime instances.
Canonical encoding is domain-versioned and UTF-8-length-prefixed; its hash is not
a live-object capability. No current source syntax or execution behavior changes.

## Specification Impact

Structs/classes move from deferred design to an explicitly implementation-in-progress
intended contract. Older struct/class exclusion language and the stale scope overview
are reconciled. Runtime/session requirements remain unimplemented acceptance gates.

## Validation

- `mvn -q -pl lyra-compiler -am test -Dtest=NominalTypeIdTest
  -Dsurefire.failIfNoSpecifiedTests=false` passed for the initial five cases.
- `mvn -q test` passed across the full reactor, including bounded core fuzz.
- `tools/fuzz-language.sh -q
  -Dtest=LanguageFuzzTest,RangeIntegrationTest,CallbackLoopIntegrationTest,NominalTypeIdTest`
  passed, including the final six identity tests, four identity seeds with 2,048
  cases each, and the existing 1,800-case-per-seed extended language/range/loop gates.
- `git diff --check` passed. Review is a recorded self-review, not independent review.
- No nominal source execution is tested or claimed; syntax/backend integration
  remains absent. No release audit or graphical qualification was run.

## Risks

This does not implement nominal syntax, resolution, value types, initialization,
ownership summaries, typed IR, emission, artifacts, Java use or persistent objects.
Existing class syntax rejection must not be mistaken for positive feature coverage.
The intended contract is ahead of the implementation, explicitly tracked by the plan.

## Follow-up Items

Complete all four active phases. Integrate authenticated nominal schemas into both
compiler/runtime type systems and metadata, not only this identity utility. Add full
source-to-JVM and persistent-session tests plus source/state fuzz models. Release
audit, benchmark and graphical qualification are not claimed by this checkpoint.
