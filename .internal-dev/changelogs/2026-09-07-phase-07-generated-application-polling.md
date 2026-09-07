# Phase 07 Generated Application Polling, Admission and Cancellation

## Date
2026-09-07

## Git Commit
419aa29c290eb23b8595e9f9a9177eeca8f523bf

## Change Summary
Activated the Phase 05 attachable dispatch effect model at real generated safe points. Attachable root closures now emit `$lyra$attachmentSafePoint` hooks at function boundaries, direct-call and callable-call sites, executable-form boundaries and direct self-tail-loop backedges; normal/session emission and dependency modules remain uninstrumented, and initialization stays inert through the lifecycle gate. The optional attachment boundary contains expected evaluation failures and cooperative cancellations through a new contained poll adapter, so main resumes without poisoning its lifecycle or cancellation context, while the ordinary `LyraOwnerController.poll()` contract is preserved. `ApplicationAttachment` now shares the registered root controller with its storage workspace, publishes owner-dispatched evaluations through a thread-safe admission path with exact request/generation binding, and reuses the poll's admitted lease instead of beginning a second one. Explicit Java-host polling services at most one pending request per safe point on the original owner without an application executor.

## Files
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/LyraOwnerController.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/ModuleLifecycle.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/SessionStorageDomain.java`
- `lyra-runtime/src/test/java/io/mindspice/lyra/runtime/RuntimeControlTest.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/backend/jvm/JvmBytecodeEmitter.java`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/ApplicationAttachment.java`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/DispatchedEvaluation.java`
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/ApplicationSafePointTest.java`
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/AttachmentCancellationTest.java`
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/SafePointMainDriver.java`
- `.internal-dev/specifications/decisions.md`
- `.internal-dev/knowledge/phase-07-attachment-dispatch-validation.md`

## Behavioral Impact
- A genuinely running attachable application services one owner request at function/call/form/self-tail boundaries; an infinite self-tail `main` services a dispatched request and resumes without nested evaluation, double leases or initialization exposure.
- Direct synchronous submission owns its lease; owner-dispatched evaluation reuses the supplied admitted context; reentrant submit/reset/close remains busy/rejected and every compile/link/runtime/cancel path clears its context in `finally`.
- Cross-thread cancellation and admission probes bind to the exact queued/active request and controller generation; late cancellation and stale service generations cannot affect new work. Terminal controller cancellation/close also completes the attachment handle and releases its admission slot, and an old dispatched handle cannot cancel a later operation even if a caller reuses its UUID.
- Java-host polling is explicit owner-thread scheduling with no app executor; handle status/result publication is safely published control metadata readable from any thread without root reads.
- Blocking host I/O or owner compilation can still delay terminal cancellation; containment is cooperative and never interrupts or terminates main.

## Specification Impact
None. The behavior delivered here is already described by the attachable safe-point, owner-polling and cooperative-cancellation contracts in `repl.md` and the Phase 05 attachable effect-boundary decision. A new durable decision in `decisions.md` records the shared-controller single-lease design and the contained poll adapter.

## Risks
- Cancellation observation inside a long-running root call is cooperative; a root function whose loop never reaches a generated boundary can delay the terminal result (accepted contract behavior, never forced interruption).
- The Lyra grammar cannot spell a `::direct[...]` call after a preceding expression form in the same block (postfix `::` binds as a member call); tests use fresh-expression positions for direct calls. This is a pre-existing parser/spec tension, worked around rather than silently changed in this phase.

## Follow-up Items
- Phase 08 managed local owner adapter and console presentation build on the dispatched evaluation handle and contained poll.
- Phase 09 transport must correlate disconnect cancellation to the exact request/controller generation using the same admitted context.
