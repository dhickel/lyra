# Phase 08 Managed Local Owner Adapter, Compatible API and Presentation

## Date
2026-09-08

## Git Commit
59d787d92cd99d5be2b071d0189d3dbaccd6743d

## Change Summary
Delivered the Phase 08 managed local owner adapter without touching the synchronous `LyraSession` API or its terminal `EvaluationResult` shapes. New `ManagedConsoleSession` opens/closes its `LyraSession` on one dedicated owner thread, admits exactly one operation at a time (evaluate/reset/query/reload) through a one-slot bounded handoff, publishes the admitted evaluation identity before execution for Ctrl-C, and supports identity-specific cross-thread cancellation with stale identities returning NOT_FOUND or an already-published operation returning `ALREADY_TERMINAL`. Teardown closes the session on the owner and retires the owner thread; no executor or worker thread outlives close. `ConsolePresentation` extracts the existing `LocalConsoleSession` record mapping so the managed adapter exposes committed declaration names/types as the same compatible `ConsoleSession.Binding` forms with no live values. Snapshot and console-boundary diagnostics are explicitly bounded: small known result budgets reject before effects, while large strings, aggregates, and source-derived diagnostic text truncate without losing terminal status. `ApplicationAttachment` explicit open/poll/close/reopen is unchanged.

## Files
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/ManagedConsoleSession.java` (new)
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/ConsolePresentation.java` (new)
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/LocalConsoleSession.java` (extraction refactor only)
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/ConsoleSession.java` (bounded console diagnostics)
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/ManagedConsoleSessionTest.java` (new, including dynamic diagnostic truncation)
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/ApplicationAttachmentJavaConsumerTest.java` (new)
- `.internal-dev/knowledge/phase-08-managed-console-validation.md`
- `.internal-dev/bugs/20260908-oversized-string-literal-emitter-crash/report.md`

## Behavioral Impact
- Local synchronous Java callers keep `LyraSession.open/submit/reset/close` and terminal `EvaluationResult` behavior; `SessionJavaConsumerTest` compiles and runs unchanged.
- Managed consoles evaluate, compile, type-check, reload, snapshot and reset on one owner thread while the console caller thread blocks on terminal results; source acquisition and generated program input keep sharing the single configured `RuntimeIoEnvironment` input owner, and history records source only.
- One operation is admitted at a time; concurrent evaluate/reset/query return BUSY outcomes and are never queued, so no second lease or nested evaluation can begin.
- Cancellation binds to the exact published evaluation identity across the pre-execution and in-flight races; stale or foreign identities cannot cancel later work.
- A second dispatched attachment request while one is pending is a truthful Busy rejection, and the consumer proves explicit owner-only polling, reopen on the retained root-lifetime domain, and teardown without application executors or leaked threads.
- Immutable `ConsoleSession` records (values, bindings, diagnostics) remain readable after reset and close; source-derived diagnostic text is escaped and capped while exact source IDs/spans remain intact.

## Specification Impact
None. The managed-console owner loop, one-operation admission, identity-published cancellation, record-shaped console presentation, preflight-versus-dynamic-truncation split, and owner teardown were already specified by `repl.md` (managed owner loop, public API, snapshots, console) and decision D11; this phase implements that contract without changing a public API or adding a generic executor/plugin surface.

## Risks
- Cancellation remains cooperative: an evaluation blocked in host I/O or an owner compile delays the terminal cancellation result (accepted contract, never forced interruption).
- The attachable scratch scope still diagnoses `::spin[]` direct self-calls as `LYC-TYPE-013`; attachment cancellation fixtures use callable-value calls, and the postfix `::` ambiguity remains an open grammar question.
- Oversized string literals (> ~64 KiB modified UTF-8) still crash the emitter with `LyraCompilerBugException`; logged in `.internal-dev/bugs/20260908-oversized-string-literal-emitter-crash/report.md` and tracked for a structured emit diagnostic.

## Follow-up Items
- Phase 09 remote v2 should reuse the admitted-operation and cancel-identity pattern for owner LOAD/RELOAD and disconnect cancellation correlation.
- Phase 10 CLI wiring: use `activeEvaluationId()`/`cancel(id)` for idle/active Ctrl-C and route the eight console commands through the managed adapter.
- Fix the oversized string literal emitter defect before Phase 13 conformance (V13 pre-execution constraint evidence).
