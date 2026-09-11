# Standalone REPL Session API

## Date

2026-09-05

## Git Commit

a16a46b71780206c6cec27ee87adef29e5333069

## Change Summary

Added the first usable `lyra-repl` session boundary. `LyraSession` owns an explicit owner thread, admits one bounded synchronous evaluation at a time, supports identity-specific cooperative cancellation, reset, close, and immutable terminal results. Submissions use the existing compiler and runtime bytecode path; single-module artifact metadata is staged and committed only after generated code is instantiated successfully. Unsupported imported/session linkage is returned as a structured diagnostic rather than simulated.

## Files

- `lyra-repl/src/main/java/io/mindspice/lyra/repl/LyraSession.java`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/SessionOptions.java`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/SessionWorkspace.java`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/SourceRecord.java`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/SourceRegistry.java`
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/LyraSessionTest.java`

## Behavioral Impact

Successful simple submissions retain public binding/module metadata and advance a monotonic session revision. Failed compilation, unsupported linkage, runtime failure, and cancellation do not publish staged names. Source text and origin metadata are retained in a bounded internal registry. No live Java values, interpreter, transcript replay, or background worker thread was added.

## Specification Impact

None. The implementation follows the existing REPL specification and intentionally leaves compiler session linking, persistent live values, expression snapshots, imports, and attached roots to later domains.

## Risks

The current one-shot compiler cannot resolve prior session bindings or execute a generated session entry point. The API therefore supports only single-source artifact execution and public artifact metadata publication; it must not be described as a complete persistent evaluator.

## Follow-up Items

Implement the compiler session linker and typed persistent execution boundary before enabling cross-submission references, mutable-cell persistence, imported module reuse, value snapshots, or attached application roots.
