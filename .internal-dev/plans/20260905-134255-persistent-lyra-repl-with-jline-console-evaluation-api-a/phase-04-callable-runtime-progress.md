# Phase 4 Progress: Initialized-generation Callable Runtime Bridge

## Context

The xhigh senior pass follows the completed source-local data aggregate slice. The accepted plan remains active and incomplete. This work is a tested physical/authentication boundary, not completion of the source-level callable persistence gate.

## Goal

Authenticate exact callable interchange between successfully initialized source-local generations without weakening ordinary AOT keys, owner/lifecycle checks, compiler sealing, or callable storage guards.

## In Scope

- Same-domain/epoch runtime authentication separate from artifact identity.
- Immutable full artifact metadata admission, including source-local classification.
- Exact generated callable parameters and higher-order returns; function-bearing arrays/tuples and aggregate signatures.
- Original closure/capture/cell identity, old lexical captures and ordinary later-source scalar assignment.
- Invocation failure/cancellation effects, producer source frames, owner/reset/close/foreign authority negative tests.

## Out of Scope

No accepted requirement is removed. Named callable persistence, callable storage accessors, transported/certified producer summaries, per-binding initialization and escaped failed/cancelled-generation retention remain unfinished. No imports/root/attachment/debug path was loosened.

## Implementation Steps

Completed: separate session authority in existing runtime tokens/linkages, complete immutable metadata pinning, epoch invalidation, source-to-bytecode runtime bridge tests, guard regressions, and scoped documentation.

Verified architectural blocker: CallableFlow and CallableSummary refer to producer lambdas/captures/FlowSiteIds in one sealed typed graph. They cannot be copied into another graph or replaced by an effect-free unknown function without breaking canonical flow and provenance. LyraSession also closes noncommitted generations, and generated initializer failure invalidates the whole producer. These paths must be solved together before removing callable guards.

## Validation

- 81 focused compiler/runtime tests passed under `-Xverify:all`, including 11 new bridge/authority tests and existing scalar/aggregate and ordinary AOT closure tests.
- Full regression counts and commands are recorded in `.internal-dev/changelogs/2026-09-06-repl-callable-runtime-bridge.md`.
- A read-only luna:high review found a mutable metadata-classification risk; the final implementation pins complete metadata immutably and tests same-ID inventory substitution.
- Pre-existing recursive captured-cell summary growth is recorded in GitHub issue https://github.com/dhickel/lyra/issues/2; runtime cancellation tests do not claim it fixed.

## Exit Criteria

The initialized-generation physical runtime bridge is implemented. The full phase-4 exit gate is NOT met. Compiler and runtime data-storage callable guards remain intact.

Next required senior escalation: parent launches configured gpt-6-astra at max (next after this xhigh pass) with edit authority. Transport certified producer callable graphs/summaries and current captured-cell flow through compiler snapshots and skeptical IR validation; implement per-binding initialization and retention for values escaping failed/cancelled execution; advance identities after such failed generations; expose original source frames through session results/protocol. Include the recursive captured-cell solver reproducer from issue #2. This first-generation child cannot launch another control-enabled senior.
