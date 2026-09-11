# Phase 4 Progress: Source-local Data Aggregate Linkage

## Context

The accepted persistent REPL plan remains active and incomplete. This pass extends the existing scalar slice; it does not mark phase 4 or the whole plan complete. Unrelated dirty, deleted and untracked worktree state was preserved.

## Goal

Deliver actual typed cross-submission arrays and tuples without replay, copied values, Object execution ABI, weakened ownership or callable authentication.

## In Scope

- Source-local arrays/tuples recursively containing non-callable data.
- Shared structural JVM type loading with exact definition and accessor checks.
- Explicit sealed external aggregate provenance and conservative may-alias writes.
- Real mutation, replacement, failure/cancellation effects, reset and snapshots.
- Repair of verbatim `:type` source handling discovered during end-to-end validation.

## Out of Scope

No accepted plan requirement is removed. Cross-generation callable-bearing values, imported graph persistence/reload, configured/application roots, asynchronous standalone execution, input coordination and debug deployment remain unfinished for subsequent work.

## Implementation Steps

Completed: compiler external profile, canonical external origin facts, semantic/IR sealing, generated exact accessors, session structural loader, runtime source-local authority checks, REPL registration and execution tests. Updated current-boundary documentation and release matrix explanations without changing BLOCKED statuses.

## Validation

- Focused `SessionAggregateLinkTest`, `PersistentAggregateTest`, scalar/result regressions and the forked Java consumer passed.
- `mvn test`: passed, 678 tests, no failures/errors/skips.
- `mvn clean verify`: passed, 678 unit tests plus 3 distribution integration tests, no failures/errors/skips.
- Includes Linux JLine PTY tests, ordinary AOT/lifecycle/authentication regressions and Java `-Xverify:all` data/cancellation execution.
- `git diff --check`: passed.
- New Phase 23 measurements/full Phase 24 audit and native macOS/Windows checks: not run.

## Exit Criteria

The non-callable data slice is executed and validated. The full phase-4 exit gate is NOT met because callable persistence remains guarded.

Exact next action: extend the immutable compiler session snapshot and canonical callable flow with retained/certified cross-generation callable identities and transfer summaries. Before enabling their accessors, separate submission execution from terminal module-construction failure, add per-binding initialization checks, and retain generations whose valid closures escape through completed effects. The later `phase-04-callable-runtime-progress.md` delivers separate authenticated runtime closure interchange for OPEN source-local generations only; it does not deliver compiler callable storage. Complete callable setter/parameter storage checks, retained-class snapshot validation and originating-source API diagnostics with the flow/lifetime path. Do not simply broaden `sameArtifact`, remove resolver guards or reopen a FAILED module.
