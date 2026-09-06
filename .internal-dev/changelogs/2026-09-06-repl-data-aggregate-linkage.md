# Persistent REPL Data Aggregate Linkage

## Date

2026-09-06

## Git Commit

86696c7598b46185c56e00c421dca8ef2392eca1

This is the inspected baseline HEAD, not a commit containing these worktree changes. Existing unrelated dirty/deleted/untracked work was preserved.

## Change Summary

Implemented source-local cross-submission arrays and tuples containing non-callable data. Storage retains original exact accessors and array identity; tuple/function-interface JVM types share a session-owned structural loader. Added explicit external array provenance through semantic and IR sealing, conservative may-alias propagation, current-state external aggregate summary substitution, and pre-execution physical MethodType checks. Kept imported/callable-bearing/partial-authority guards. Reset retires binding/type epochs. Fixed `:type` command parsing so literals, escapes and source whitespace are not stripped as shell arguments.

## Files

Compiler:
- `compiler/session/ExternalBinding.java` and `semantic/SemanticResolver.java`: supported data profile and guarded external admission.
- `semantic/SemanticFlowAnalyzer.java`, `SemanticFlowFactValidator.java`, `flow/ArrayIdentity.java`, `CallableSummaryCompiler.java`, `CallableSummarySet.java`: explicit external origins, may-alias handling and current-state summary substitution.
- `ir/IrAggregateProvenance.java`, `IrAggregateAllocation.java`, `IrFlowMetadata.java`, `IrValidator.java`: external contract-origin projection and sealing; ordinary allocations still require sites.
- `backend/jvm/GeneratedTypePlanner.java`, `GeneratedClassPlan.java`, `GeneratedMemberKind.java`, `GeneratedMemberPlan.java`, `JvmAbiParity.java`, `JvmBytecodeEmitter.java`: typed data accessors, dependency parity, deterministic shared types, session-only tuple getter visibility and early physical checks.

Runtime (`lyra-runtime/src/main/java/io/mindspice/lyra/runtime/`):
- New `SessionTypeLoader.java`; updated `SessionStorageDomain.java`, `LyraRuntime.java`; corrected `ModuleLifecycle.java` accessor documentation.

REPL (`lyra-repl/src/main/java/io/mindspice/lyra/repl/`):
- `LyraSession.java` registers executed data storage.
- `ConsoleCommandParser.java` preserves `:type` source verbatim.

Tests:
- New `lyra-compiler/.../api/SessionAggregateLinkTest.java` and `lyra-repl/.../PersistentAggregateTest.java`.
- Updated `SessionCompilerTest`, `PersistentScalarTest`, `LyraSessionTest`, `SessionJavaConsumerTest`, `PlainConsoleTest`, `ConsoleParsingTest`. Unsupported tests now exercise genuinely unsupported callable-bearing data instead of resolved plain arrays.

Records:
- `README.md`, `AGENTS.md`, living REPL/backend/decision specifications, domain knowledge, phase-4 progress record and `tools/phase24-requirement-matrix.tsv` explanations. No release BLOCKED row was promoted to PASS.

## Behavioral Impact

Arrays retain actual aliases through element mutation and ordinary rebinding. Nested tuple/array values, nullable/unsigned members and current-submission closures consuming/returning retained data work through direct bytecode. New data assigned before runtime failure/cancellation remains usable without publishing failed names. Type/compile/link failures perform no source effects; capability, logical type, JVM type, loader, owner, revision and reset checks remain fail-closed. AOT tuple visibility and artifact-local closure authentication are unchanged.

Validation: focused persistence tests passed; `mvn test` passed 678 tests; `mvn clean verify` passed 678 unit tests plus 3 distribution integration tests, all without failures/errors/skips. Includes Linux JLine PTY, normal backend/runtime regressions and forked Java `-Xverify:all` aggregate/cancellation execution. `git diff --check` passed. Standard deprecation/manifest-overlap warnings remain non-failing.

## Specification Impact

Documented the delivered source-local data profile and its exact compiler/runtime boundaries in `repl.md` and `backend-runtime.md`, plus the explicit external-origin and loader tradeoffs in `decisions.md`. Intended full persistent REPL behavior is unchanged and remains incomplete.

## Risks

Function values and all callable-bearing aggregates remain unsupported across generations. Shared function interfaces do not provide callable flow summaries, cross-generation closure authority, per-binding initialization guards, escaped failed-generation retention or delayed producer source maps. Imports/pinned reuse/reload, configured/application roots, owner-executor/input coordination and debug launcher/distribution integration remain unfinished. Total heap/metaspace reclamation or class unloading is not guaranteed. Native macOS/Windows checks, new Phase 23 measurements and a full Phase 24 audit were not run.

## Follow-up Items

Execute the next callable-linkage action in the phase-4 progress record before removing callable guards. Imported provenance and module ownership must be implemented before loosening the single-module aggregate authority restriction. Full plan completion remains BLOCKED.
