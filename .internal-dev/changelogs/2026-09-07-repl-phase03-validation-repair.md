# Phase 03 REPL Validation and Repair

## Date

2026-09-07

## Git Commit

07c019342ecd5a0b5f9a44be52a00a8ff57e86d5

## Change Summary

Validated and repaired the actual uncommitted Phase 03 worktree. Prepared session graphs now use one registered shell graph with deferred guarded execution, exact retained-producer access, graph-wide source-capacity preflight, and correct cleanup. Imported callable mutation checks distinguish producer-owned captured state from caller-owned imported aliases. Ordinary AOT initialization retains canonical dependency order.

## Files

- JVM planner/emitter and compiler session execution projection.
- Runtime artifact keys, prepared module views, lifecycle cleanup, and session storage linkage.
- `LyraSession` and `SourceRegistry` staging, graph retention, and producer-qualified registration.
- Focused imported-module/runtime integration tests.
- `knowledge/repl-module-linkage.md` and the Phase 03 validation review.

## Behavioral Impact

- Stateful imported modules initialize once and preserve scalar, aggregate, and callable identity across submissions.
- Imported module functions may mutate state owned by their producer; direct imported rebinding and caller-owned mutable aggregate transfer remain rejected.
- Link and source-capacity failures happen before Lyra effects; failed submissions retain completed effects but publish no staged names.
- Retry reruns failed initializers and supports changed source revisions within explicit source bounds.
- Intrinsic `std->io` print/readLine executes through one configured runtime I/O environment and preserves the following submission.

## Specification Impact

None. The existing REPL specification already requires imported-module reuse, producer ownership, staged publication, nontransactional effects, and real intrinsic I/O. Historical backend-runtime wording about the earlier source-local profile should be reconciled during the later specification/documentation pass rather than changed silently here.

## Risks

Later reload, application-root attachment, and safe-point phases are not validated by this review. Unrelated worktree edits and deletions remain present by design.

## Follow-up Items

- Preserve exact producer and lifetime evidence when implementing reload and application-owned borrowed graphs.
- Keep the Phase 03 focused tests in the release audit inventory.
