# Phase 13 Cross-Surface Conformance

## Date

2026-09-08

## Git Commit

9ee3f02acda2988752b737ee8aac057f09e633ae

## Change Summary

Completed and repaired Phase 13 cross-surface conformance on the committed Phase 12 baseline. One shared source corpus now executes through the synchronous local session, managed owner adapter, plain console, rich JLine console, credential-free remote-v2 standalone transport and actual attached application. Existing Java consumer, owner-poll, cancellation, activation, packaging and forked-lifetime suites provide the remaining host/deployment evidence.

The repair preserves selected summaries from mixed current/certificate-only callable SCCs, reuses exact pinned source snapshots across later graphs, retains attached root and scratch-producer source inventories across reset/detach/reopen, diagnoses oversized JVM modified-UTF-8 string constants before class emission, and prevents ordinary connection cleanup from racing away graceful-shutdown terminal frames. Tests cover persistent state and lexical replacement; source-local/imported higher-order values and recursion; arrays, tuples and callable aggregates; std->io; duplicate imports; ownership diagnostics; changed reload topology and old references; source/UTF-16 fidelity; source/envelope preflight versus dynamic truncation; attached root mutation, borrowing, reset/reopen/root-close lifetime; no-auth remote state; and normal AOT isolation.

## Files

- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/backend/jvm/JvmBytecodeEmitter.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/CallableSummarySet.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/source/ModuleGraphDiscovery.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/backend/jvm/Phase15SmokeTest.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/SessionStorageDomain.java`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/ApplicationAttachment.java`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/SourceRegistry.java`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/remote/RemoteServer.java`
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/CrossSurfaceCorpus.java`
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/CrossSurfaceLocalApiTest.java`
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/CrossSurfaceManagedConsoleTest.java`
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/CrossSurfacePlainConsoleTest.java`
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/CrossSurfaceAttachedAppTest.java`
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/ReloadSequenceReproTest.java`
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/remote/CrossSurfaceRemoteStandaloneTest.java`
- `lyra-cli/src/test/java/io/mindspice/lyra/cli/JLineConsoleTest.java`
- `.internal-dev/bugs/.archive/20260906-recursive-captured-cell-summary-limit/report.md`
- `.internal-dev/bugs/.archive/20260908-oversized-string-literal-emitter-crash/report.md`
- `.internal-dev/bugs/.archive/20260908-remote-shutdown-terminal-race/report.md`
- `.internal-dev/knowledge/cross-surface-conformance-validation.md`
- `.internal-dev/reviews/2026-09-08-phase13-cross-surface-conformance-review.md`

## Behavioral Impact

- Later compilation against a pinned import cycle no longer fails the callable-summary coverage invariant.
- Sequential reloads with retained imports preserve the producer's original source identity and accept changed import topology without retargeting old callable edges.
- Attached root, dependency and root-held scratch closure failures retain original source excerpts across reset, detach and service reopening; attachment opening rejects unrepresentable root or retained-producer source context before service admission.
- Oversized modified-UTF-8 string literals return `LYC-EMIT-001`; they no longer escape as compiler bugs or execute earlier forms in the rejected submission.
- Graceful service close reliably delivers a queued request's `CANCELLED` terminal result before closing its transport; repeated shutdown-race runs no longer degrade it to `DISCONNECTED`.
- No public API, normal AOT ABI, credential surface, module dependency edge or runtime execution mode was added.

## Specification Impact

Specification Impact: none. The edits implement the accepted `repl.md` and `backend-runtime.md` source, ownership, lifetime, direct-bytecode and pre-effect rejection contracts without changing them.

## Risks

Conservative generation/root-lifetime retention remains intentional and makes no class-unloading claim. Cooperative cancellation can still be delayed by blocking host I/O or owner compilation as specified. Phase 14 release audit, documentation/matrix reconciliation and fresh Phase 23 evidence remain separate work.

## Follow-up Items

- Execute Phase 14 only under separate authorization; do not treat this Phase 13 record as the release audit.
