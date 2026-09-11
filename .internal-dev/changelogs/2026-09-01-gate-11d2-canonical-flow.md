# Gate 11D.2 — Canonical typed flow/eager analysis

## Date

2026-09-01

## Git Commit

c02851d4a3a56ac652079724664e7de2224c6295

## Change Summary

Added the canonical typed semantic-flow evaluator and immutable internal flow facts. Typed expressions are normalized, solved 11D.1 callable summaries are applied to source-ordered module initializers, and compact declaration/call/mutation/capture/effect events plus eager witnesses are published. Unknown aggregate selectors retain finite reachable callable unions, active value re-entry is separated from completed traversal, captured shared-cell writes persist through calls, and function-only recursive call cycles remain distinct from eager value cycles.

Initialization analysis now delegates flow/effect reconstruction to `SemanticFlowAnalyzer`; `InitializationAnalyzer` only plans dependencies, cycles, SCC witnesses, and deterministic topological order from canonical eager-effect facts. Summary transfer preserves concrete callable-cell writes and treats scalar, declaration-backed, and fresh caller-local callback writes as complete non-escaping transfers.

The completion repair separated short-circuit continuation from joined exit state, seeded truthy predicate bindings, made direct calls honor current lambda-local/captured rebinding, retained eager capture-read evidence on closure events, and added explicit intrinsic declaration/alias discovery through solver and higher-order transfer.

The blocker repair added branch-safe exact formula override masks, separated actual call formulas from caller write-target formulas, retained actual caller declaration identities on writes, and removed synthetic write placeholders. Immutable captured aggregate identities may now be mutated through a local `@mut` alias without inventing a capture-cell write; imported ownership remains enforced by the resolver. Eager cycle witnesses now start at the repeated active declaration, and planner witnesses normalize to the matching dependency SCC.

## Files

- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/SemanticFlowAnalyzer.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/InitializationAnalyzer.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/SemanticFlowFacts.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/SemanticFlowResult.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/SemanticFlowEvent.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/EagerEffectFact.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/EagerCycleWitness.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/CallableSummaryCompiler.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/CallableSummary.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/CapturedCellWrite.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/FormulaAlternatives.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/CallableSummarySet.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/CallableSummarySolver.java`
- `lyra-compiler/src/test/java/CallableSummaryTest.java`
- `lyra-compiler/src/test/java/Domain11InitializationFlowTest.java`
- `.internal-dev/knowledge/semantic-aggregate-provenance.md`

## Behavioral Impact

Typed graph initialization plans now derive from one canonical typed flow/effect route. Existing aggregate aliases, source-order replacement, higher-order calls, returned closures, recursive summaries, eager cycle diagnostics, and deterministic ordering remain passing; function-only cross-module call recursion is not reported as an eager value cycle. Predicate-bound callables, three-operand short-circuit continuation, captured direct-call rebinding, nested capture-read events, concrete aggregate callback writes, direct/higher-order intrinsic calls, exact alias replacement/invocation, immutable-capture aggregate mutation, and precise cycle suffix membership now have focused regressions.

## Specification Impact

Specification Impact: none. The implementation stays within the accepted Gate 11D.2 internal semantic-flow and initialization-planning contract and does not enter 11E graph sealing or phase 12.

## Risks

Semantic-flow facts are intentionally not attached to `TypedSemanticGraph` yet; that publication/sealing boundary remains Gate 11E. Resolver-time flow remains only the accepted early ownership/mutation law; no resolver behavior was removed or changed. The repository's pre-existing dirty, deleted, and untracked work was preserved.

## Follow-up Items

Gate 11E may attach and validate the immutable facts through the package-owned graph sealer without reinterpreting source syntax. Gate 11D.2 blocker validation passed 64 focused callable/initialization tests and 217 clean compiler-module tests; `mvn clean verify` and `git diff --check` also passed.
