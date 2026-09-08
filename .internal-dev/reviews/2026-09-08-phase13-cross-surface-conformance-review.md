# Phase 13 Cross-Surface Conformance Review

## Scope

Reviewed and validated the Phase 13 worktree against the accepted R01-R18 contract and Phase 13 exit. Scope included the common source corpus, compiler summary/source/emitter repairs, local/managed/plain/JLine/remote/attached surfaces, existing application safe-point and cancellation races, root-held lifetime cases, host Java consumers, activation/shutdown, file/load/completion limits, debug packaging, normal AOT isolation and the four-module reactor. Phase 14 audit scripts, release matrix, documentation and Phase 23 rerun were intentionally not changed or executed.

## Findings

- The abandoned worker's tests mixed real defects with incorrect assertions. Production defects were mixed-SCC summary selection, pinned-snapshot recapture, missing attached root/scratch source retention across reset and reopen, unchecked oversized string constants, and a graceful-shutdown handler race that could clear a queued terminal frame.
- Test-only errors included treating snapshot-local `fn1` labels as global identities, invoking a generated method on `ModuleHandleImpl`, expecting root initializer output from attachment-only I/O, reading the endpoint warning from `toString`, expecting numbered history-file storage, and asserting access to an internal dependency alias that had never entered scratch scope.
- Repaired cross-surface tests now prove persistent counter/private replacement, imported and source-local higher-order behavior, recursion and import cycles, arrays/tuples/callable aggregates, std->io, duplicate imports, ownership diagnostics, old/new reload values and changed topology, exact delayed source spans, dynamic truncation, no-auth transport and normal AOT isolation.
- Attached tests prove real root storage mutation in both directions, borrowed app modules, shared structural classes over close/reopen/reset, root-close invalidation, source-context preflight, and exact root/root-held-scratch excerpts after reset and service reopening. Existing `AttachedValueLifetimeTest`, `ApplicationSafePointTest`, `AttachmentCancellationTest`, remote robustness/file tests and Phase 12 activation suites cover failure/cancel escapes, transitive borrowing, admission/disconnect/late-cancel races, server-side paths/completion and init/wait/main shutdown.
- An initial final `mvn -q test` rerun exposed the shutdown race as `CANCELLED` degrading to `DISCONNECTED`. After repair, the exact forked shutdown method passed five consecutive runs. Final Java 25 `mvn -q test` passed, followed by `mvn -q clean verify`: 964 unit tests plus 16 integration tests passed with zero failures, errors or skips.

## Risk Assessment

Low within Phase 13 scope. Callable summary publication now preserves coverage without using selected mixed components as a later solver input. Pinned snapshots retain their exact origin identity. Root-lifetime source retention consumes configured source capacity deliberately, survives service replacement without reviving history, and fails before service admission when the requested limits cannot represent retained producers. Modified-UTF-8 preflight matches JVM constant encoding and rejects rather than splitting. Server-wide close now exclusively owns terminal drain and transport retirement, while ordinary peer disconnect keeps its prior cleanup path. Conservative producer retention and cooperative cancellation remain explicit product constraints, not defects.

## Recommendations

Preserve the common corpus and exact focused groups during Phase 14 matrix construction. Map runtime identity assertions to Lyra `eq?`, keep presentation/storage encodings distinct, and retain root source inventory whenever attachment reset logic changes. Do not weaken the oversized-literal preflight or mixed-SCC coverage to obtain a green audit.

## Follow-ups

- Phase 14 owns release documentation, exact requirement-matrix reconciliation, fresh Phase 23 performance evidence and independent release review.
- Native macOS/Windows terminal behavior remains N/A because this validation executed on Linux only.
