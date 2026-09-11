# Phase 11 Callable Declaration Flow Repair

## Date

2026-09-02

## Git Commit

c02851d4a3a56ac652079724664e7de2224c6295

## Change Summary

Repaired two scoped Phase-11 canonical callable-flow defects without changing source-language semantics or entering Phase 12.

Direct and namespace summary calls no longer assume that direct-call syntax implies a statically fixed lambda. Parameter, capture, and prior call-result targets remain symbolic until ordinary caller substitution supplies their exact finite callable identities. Solver validation still rejects unknown, identityless, absent, and mixed unrecoverable target facts as `MISSING_CALLABLE_FACT`. Calls whose target, arguments, or nested closure environment contain caller-dependent callable structure are deferred consistently, and caller-time transfer retains their writes, ownership requirements, effects, and original direct/namespace/callable kind.

Function-valued declarations initialized by calls, projections, blocks, or other computed expressions are now classified in the existing callable-identity discovery pass rather than being treated as declaration-to-lambda aliases. Their summaries retain exact declaration formulas. The canonical `SemanticFlowAnalyzer` resolves those formulas from the current source-ordered declaration value through its existing `ensure`/flow state, converts the exact callable identity and capture/shared-cell snapshots, and preserves producer-issued creation/effect sites. Imported namespace function values receive eager value-read evidence where initialization is required. No compatible-signature lambda search or second evaluator was added.

## Files

- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/CallableSummaryCompiler.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/CallableSummarySolver.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/CallableSummarySet.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/SemanticFlowAnalyzer.java`
- `lyra-compiler/src/test/java/CallableSummaryTest.java`
- `lyra-compiler/src/test/java/Domain11AggregateOwnershipTest.java`
- `lyra-compiler/src/test/java/Domain11InitializationFlowTest.java`
- `.internal-dev/knowledge/semantic-aggregate-provenance.md`
- `.internal-dev/changelogs/2026-09-02-phase-11-callable-declaration-flow-repair.md`

## Behavioral Impact

Valid direct calls through callable parameters, captures, and call results now compile and transfer exact caller facts. Local, selective-import, and namespace-import computed function values can be returned, callable-invoked, direct-invoked, namespace-invoked, passed through higher-order direct calls, and reconstructed with nested immutable captures or shared cells. Source-ordered same-name or mutable function declarations and projected same-signature alternatives retain the exact producing lambda identity. Mutable recursive slots retain bounded SCC linkage, while an escaped local recursive lambda resolves its owner to the currently invoked callable rather than fabricating a module declaration value. Computed imported value reads contribute initialization dependencies, and transferred shared-cell writes and imported-ownership diagnostics retain their existing shapes.

True missing callable identities remain internal semantic-artifact failures and never become effect-free behavior or a source diagnostic. Direct, namespace, callable, parameter, and capture call kinds remain distinct. The producer-certified `FlowSiteId` publication path and one canonical semantic-flow invocation are unchanged.

Validation evidence at closeout:

- Focused callable/flow/ownership matrix: 120 tests passed.
- `mvn -pl lyra-compiler clean test`: 283 tests passed.
- `mvn clean verify`: four-module reactor passed with 283 compiler tests.
- `git diff --check`: passed; a separate trailing-whitespace audit covered the edited untracked module/store files.

## Specification Impact

Specification Impact: none. The repair enforces the existing parameterized-summary, exact callable identity, source-order, eager initialization, ownership, and explicit missing-fact contracts. It does not alter the intentionally accepted same-package topology-certificate exception in `specifications/decisions.md`.

## Risks

The declaration resolver overload is an internal cross-package compiler hook on an already internal summary model; it is not a stable product API or serialized fact. It accepts only the canonical analyzer's exact current declaration values on the production path. Computed callable SCC edges remain caller-time symbolic when identity genuinely depends on eager declaration evaluation; ordinary statically linked recursive SCC behavior is unchanged.

The worktree's pre-existing deleted prototype files, parent/module layout, and unrelated untracked records were preserved. This record does not declare Gate 11E or overall Phase 11 passed.

## Follow-up Items

- Obtain the requested independent read-only validator verdict for these two blockers.
- Do not begin Phase 12 or declare Gate 11E/Phase 11 passed from this repair record.
