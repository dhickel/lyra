# Persistent REPL Callable Persistence Closeout

## Date

2026-09-06

## Git Commit

86696c7598b46185c56e00c421dca8ef2392eca1

## Change Summary

Completed the source-local compiler-side named callable persistence slice for the Lyra REPL and repaired the remote cancellation admission race. Callable summaries and flow certificates now preserve callable identity, captured cells, writes, allocation provenance, operation-site spans and generation authority across direct-bytecode submissions.

## Files

- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/api/SessionFlowCertificate.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/CallableSummaryCompiler.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/CallableSummarySolver.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/SemanticFlowAnalyzer.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/api/LyraCompiler.java`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/LyraSession.java`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/remote/LyraSessionAdapter.java`
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/PersistentCallableTest.java`
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/LyraSessionTest.java`
- Relevant specifications, knowledge, audit matrix and review records.

## Behavioral Impact

Source-local higher-order calls, returned closures, recursive functions, callable-bearing arrays/tuples, lexical replacement, captured mutable-cell updates, failed-submission escape behavior and cooperative cancellation use retained generated code and storage. Retained callables remain fail-closed for wrong signatures, foreign sessions, retired generations and imported multi-module graphs. A remote cancellation probe is checked after session active-operation admission, preventing cancellation from being lost in the token-to-session handoff.

## Specification Impact

The REPL specification and repository guidance now describe compiler-certified source-local callable persistence and distinguish it from still-incomplete imported-module, reload, configured-root and application-attachment behavior.

## Risks

The full REPL contract remains incomplete for imported/pinned module linkage, reload, configured roots, application attachment, safe points, coordinated program input and debug deployment. The Phase 24 audit remains BLOCKED for those requirements.

## Follow-up Items

Implement the remaining module/workspace and application-attachment phases as separate owner- and lifecycle-validated changes without weakening the source-local callable certificate boundary.
