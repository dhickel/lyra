# Gate 11D.1 typed callable summaries

## Date

2026-09-01

## Git Commit

c02851d4a3a56ac652079724664e7de2224c6295

## Change Summary

Added an internal JVM-independent typed-expression normalizer and immutable symbolic callable-summary model. The model records ordered parameter/capture placeholders, routed return formulas, fresh allocation sites, branch alternatives, parameter/captured-cell writes, direct/namespace/callable/capture call references, and eager-effect witnesses. A deterministic finite-domain solver computes callable SCC least fixed points and reports missing callable facts as explicit internal failures without adding a source diagnostic.

Second-round Gate 11D.1 repair made known-call substitution recursive through returned closure captures and write values, propagated transferred higher-order effects, retained concrete fresh/declaration-backed captured-cell targets, and enforced finite formula/route bounds before dynamic target enumeration. Four focused regressions cover those boundaries.

## Files

- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/CallableCallReference.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/CallableSummary.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/CallableSummaryCompiler.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/CallableSummaryResult.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/CallableSummarySet.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/CallableSummarySolver.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/CapturedCellWrite.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/EagerEffectWitness.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/FormulaAlternatives.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/FreshAllocationSite.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/NormalizedExpression.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/SummaryCallId.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/SummaryLimits.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/SummaryTransferResult.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/TypedExpressionNormalizer.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/ValueFormula.java`
- `lyra-compiler/src/test/java/CallableSummaryTest.java`

## Behavioral Impact

The summary layer is not wired into `InitializationAnalyzer`, graph sealing, or publication. Existing semantic production behavior remains unchanged. Summary-only analysis preserves finite reachable callable alternatives, recursively substituted closure/write facts, transitive eager witnesses and writes, concrete captured-cell identities, and function-only recursion while refusing to turn absent callable facts into effect-free results.

Validation after the repair: focused `CallableSummaryTest` passed 24 tests; `mvn -pl lyra-compiler clean test` passed 177 tests; `mvn clean verify` passed the four-module reactor with 177 compiler tests; and `git diff --check` passed.

## Specification Impact

Specification Impact: none. This gate implements the accepted Phase 11D.1 internal summary contract without changing source-language semantics or entering the later flow/eager cutover or sealing gates.

## Risks

- Intrinsic summary registration and production initialization consumption remain for Gate 11D.2.
- SemanticFlowFacts publication and graph sealing remain for Gate 11E.
- The current worktree contains unrelated dirty, deleted, and untracked project work that was preserved.

## Follow-up Items

- Gate 11D.2 may consume the solved summaries for canonical source-ordered flow/eager analysis only after its focused gate is accepted.
