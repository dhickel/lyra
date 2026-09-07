# Phase 07 Attachment Dispatch Validation

## Topic

Validating generated application safe points, owner-dispatched evaluation admission, and cooperative attachment cancellation in the direct-Java-25-bytecode Lyra runtime.

## Source References

- `.internal-dev/plans/20260906-221525-complete-lyra-repl-with-trusted-module-linking-and-local/plan.md` (Phase 07, D11/D12, V08–V11)
- `.internal-dev/specifications/repl.md`, `backend-runtime.md`
- `LyraOwnerController`, `ModuleLifecycle`, `SessionStorageDomain`, `ApplicationAttachment`, `JvmBytecodeEmitter`
- `ApplicationSafePointTest`, `AttachmentCancellationTest`, `RuntimeControlTest`, `SafePointMainDriver`

## Key Takeaways

- **One controller, one lease.** A live attachment must install its registered root controller into its `SessionStorageDomain` so synchronous submissions, owner-dispatched evaluations and generated application safe points all observe the same active lease. Before this sharing, a sync evaluation's lease lived on a workspace controller the root hooks never consulted, so generated safe points could have begun nested work.
- **Contain at the boundary, keep the adapter.** `poll()` rethrows dispatch failure/cancellation for ordinary controller consumers; `pollContained()` records the same terminal status on the `Dispatch` and swallows the signal. `ModuleLifecycle.applicationSafePoint()` uses the contained form so a dispatched evaluation failure/cancel never unwinds into main. Do not change the ordinary contract; add the adapter.
- **Reuse the admitted lease, never begin a second.** Owner-dispatched evaluation gets the poll's active lease through `controller.currentEvaluation()` and `SessionStorageDomain.beginEvaluation(admitted)` only validates controller membership plus liveness. The sync path ends its own lease in `finally`; the dispatched path lets the poll's `finally` end it exactly once.
- **Terminal publication races lease release.** `awaitResult()` returns as soon as the handle publishes, which can precede the poll's post-safe-point and `endEvaluation`. Cross-thread admission must therefore probe the controller's safely published atomics (`hasLiveWork()`) and map the residual race to a truthful Busy outcome; hosts retry transient Busy rather than treating it as a defect.
- **Initialization can never dispatch by construction.** `registerRoot` requires an OPEN lifecycle, so a pending request cannot exist during root initializers; the INITIALIZING gate plus the inert null-controller hook are defense in depth. A second root's construction with a pending request on the first service must not consume it (proven behaviorally).
- **Lyra grammar gotchas for test sources.** (1) `::name[args]` after any expression form in the same block is parsed as a *member* direct call on that expression (postfix `::`); standalone direct calls only parse in fresh expression positions (initializer RHS, branch body, first form, or after a `let`). (2) `Unit` is falsey and assignment is Unit-typed, so assignment-as-predicate tricks are not truthy side-effect expressions. (3) `(name args)` is a callable-value call with real recursion frames; only `::name[args]` participates in direct self-tail loop lowering.
- **Forked-JVM handshake pattern.** An infinite self-tail `main` never returns, so drive it in a forked JVM: owner thread prints READY, invokes `main`, and the control thread dispatches evaluations, asserts SERVICED/RESUMED markers, and calls `System.exit(0)`. Parent asserts markers and exit code with a bounded waitFor; resume is proven by a later request being serviced at a later safe point.
- **Terminal removal must complete the attachment handle.** A controller can cancel or close a pending dispatch without ever running its operation. An attachment-side terminal observer must clear admission and publish the corresponding cancelled/closed result; otherwise `awaitResult()` and later submissions can remain stranded even though the controller is idle.
- **Handle cancellation needs object identity in addition to request UUID.** Caller-supplied `EvaluationId` values can be reused accidentally. `DispatchedEvaluation.cancel()` therefore matches the exact admitted handle, while the explicit ID-based control API remains the current-request operation; a late old handle must not cancel a later request with the same UUID.

## Project Relevance

Phase 08 (managed owner adapter), Phase 09 (transport correlation of disconnect/cancel) and Phase 12 (run/compile activation) build directly on the dispatched evaluation handle, the contained poll, and the single-leash controller.

## Open Questions

- Whether the `::direct[...]` postfix ambiguity in blocks/submissions should be repaired in a dedicated grammar phase; the language spec intends `::callee[args]` as a general form.
- Whether transient Busy on terminal-publication races should become an internal retry policy for local transport adapters rather than a caller-visible outcome.
