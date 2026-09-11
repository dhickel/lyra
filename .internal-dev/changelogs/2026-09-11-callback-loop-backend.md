# Date

2026-09-11

# Git Commit

39796802041b8f6eb7cbb0062b5231aeb9c7a70c (baseline before this change)

# Change Summary

Complete the reserved iter and while forms through contextual resolution,
type checking, repeated semantic/callable flow, closed typed IR and direct JVM
execution. Finish range persistence and source-mapped zero-step failures.

# Files

- Compiler semantic/flow layers, IR/visitor/validators and JvmBytecodeEmitter.
- Runtime LyraRange/SessionStorageDomain and compiler ExternalBinding.
- REPL range snapshots/storage, console rendering and protocol regression tests.
- CallbackLoopIntegrationTest, persistent session/cancellation tests, snapshot
  tests, conformance corpus, source fuzz worker/oracle and extended campaign tool.
- Living language/backend/REPL specs, decisions, coverage and range knowledge;
  archived completed range-iter plan and resolved self-rebinding bug #5.

# Behavioral Impact

- Both call spellings execute iter with exact zero/one-argument Unit callbacks,
  and while with exact zero-argument Bool predicate and Unit action. Both return
  Unit. Callback arguments are selected once; shared captures remain live.
- Repeated ownership/callable effects reach subsequent expressions, including
  after an enclosing function returns. Later-iteration imported mutation remains
  rejected. Mutable function self-rebinding preserves the selected identity.
- Primitive cursor/step locals, successor-before-addition checks, hoisted selected
  callback authentication and constant-stack loops avoid iterator/counter and
  per-iteration result allocation. Existing invocation lifecycle checks and owner
  safe points remain active. No quantitative benchmark speedup is claimed.
- Ranges retain exact signed widths through host/session boundaries and aggregate
  storage. Snapshots remain bounded immutable data, never traverse a range, and
  survive the existing scalar wire representation. Dynamic zero-step failures
  carry arithmetic source sites; callback failures preserve call frames.

# Specification Impact

language-core.md now identifies executable iter/while and persistent ranges.
backend-runtime.md specifies loop IR, repeated summaries and emission boundaries.
repl.md specifies range snapshots and loop cancellation. decisions.md records the
implementation tradeoffs; docs/language-testing.md includes the eleventh source
fuzz mode and independent loop oracle.

# Risks

Repeated summaries deliberately use conservative joins and existing finite
analysis budgets; complex programs may hit those budgets rather than silently
lose ownership facts. Range domains remain I8/I16/I32/I64. This change does not
introduce break/continue, do-while or implicit callback result dropping. Existing
legacy artifact compatibility tests pass; this is not release/ABI qualification.

# Follow-up Items

Validation: full `mvn -q test` passed across all modules, including default fuzz,
compiler/IR, runtime, REPL, CLI and editor tests. `tools/fuzz-language.sh -q`
passed four source seeds (1, 24301, 8675309, 9223372036854775807), each at 1,800
cases, plus range and executed-loop models at 1,800 cases per signed width.
Focused callable-summary and backend integration regressions also passed.
Final diff review and `git diff --check` found no whitespace defects.

No required feature scope remains. Run the mandatory Phase 24 release audit
before a release; it was not run for this feature commit. No push performed.
