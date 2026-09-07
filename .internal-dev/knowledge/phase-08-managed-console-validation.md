# Phase 08 Managed Console Adapter Validation

## Topic

Building the internal managed-console owner adapter for the local console surface, one-operation admission, identity-specific cross-thread cancellation, and console-boundary presentation in the direct-Java-25-bytecode Lyra REPL.

## Source References

- `.internal-dev/plans/20260906-221525-complete-lyra-repl-with-trusted-module-linking-and-local/plan.md` (Phase 08, R10/R12/R13, D11, V12/V13)
- `.internal-dev/specifications/repl.md` (managed owner loop, public API shapes, snapshots, console)
- `LyraSession`, `ConsoleSession`, `LocalConsoleSession`, `PlainConsole`, `ConsoleInputCoordinator`, `SnapshotReader`, `ApplicationAttachment`, `DispatchedEvaluation`
- `ManagedConsoleSessionTest`, `ApplicationAttachmentJavaConsumerTest`, `SessionJavaConsumerTest`

## Key Takeaways

- **One operation, published identity, no executor API.** `ManagedConsoleSession` owns a `LyraSession` created/executed/closed on one dedicated daemon thread fed by a one-slot bounded handoff queue. Admission is guarded by one monitor: a second evaluate/reset/query while an operation is admitted returns BUSY instead of queueing, so no double lease or nested evaluation can begin. The admitted evaluation's `EvaluationId` is visible through `activeEvaluationId()` before the owner starts executing it, which is the Ctrl-C surface. Cancellation reports `ALREADY_TERMINAL` if the owner has published a terminal evaluation before the adapter releases its admission slot.
- **Cancellation has two cooperative layers.** `cancel(id)` marks the adapter-level `AtomicBoolean` probe and forwards to `LyraSession.cancel(id)`. The session's admission-time probe covers the pre-execution race; the session's cross-thread cancel covers an already-running evaluation at generated boundaries. A stale or foreign identity returns NOT_FOUND and can never touch later work. Queue admission uses non-blocking offer with terminal cleanup, so a full handoff cannot strand the operation or leave the busy gate held.
- **Teardown must retire the thread.** `close()` rejects while an operation is active (mirroring the local API), then queues a shutdown marker; the owner closes the session on itself, exits the loop, and the caller awaits an owner-exit latch plus `join`. Every stranded operation is failed in a finally drain so no caller can block forever if the owner dies unexpectedly.
- **The presentation boundary is record-shaped, not live.** `ConsolePresentation` extracts the exact `LocalConsoleSession` mapping (evaluation/status/binding/diagnostic rendering) so both console targets produce identical immutable `ConsoleSession` records; committed names/types are `ConsoleSession.Binding` records and aggregates render their identity, never live values. Source-derived diagnostic summaries and relation labels are escaped and explicitly capped at the console boundary; source IDs and spans remain exact.
- **Attachment Java consumers must retry the documented terminal-publication race.** `DispatchedEvaluation.awaitResult()` returns before the poll's `finally` ends the lease, so a submitDispatch racing that window is rejected with a truthful Busy. The consumer proves the race by synchronizing through an owner-side idle handshake (poll-empty evidence) before admitting the next request; hosts retry transient Busy rather than treating it as a defect.
- **Scratch direct calls differ between profiles.** `::spin[]` self-tail spelling compiles in local session submissions but the attachable scratch scope diagnoses it as `LYC-TYPE-013` (member direct calls require a statically declared callable member). Attachment cancellation tests use the callable-value call `(spin)` like the existing Phase 06/07 fixtures.
- **JVM literal limit is a real pre-execution constraint.** A string literal above ~64 KiB modified UTF-8 crashes the emitter with `LyraCompilerBugException` (see `bugs/20260908-oversized-string-literal-emitter-crash`). Snapshot display truncation is a separate, later boundary: a 20 KiB literal preflights at the minimum envelope and truncates dynamically with `RENDERED_OUTPUT` after its effects run.

## Project Relevance

Phase 09 transports use the same admitted-operation/cancel-identity pattern; Phase 10 wires CLI Ctrl-C through `activeEvaluationId()`/`cancel(id)` and routes the eight console commands through the managed adapter.

## Open Questions

- Whether the `::direct[...]` postfix ambiguity (diagnosed differently in attachable scratch scope) should be repaired in a dedicated grammar phase.
- Whether the transient Busy on terminal-publication races should become an internal retry policy for local transport adapters rather than a caller-visible outcome.
