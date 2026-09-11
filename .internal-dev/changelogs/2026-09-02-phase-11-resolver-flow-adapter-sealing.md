# Phase 11 Resolver Flow Adapter Sealing

## Date

2026-09-02

## Git Commit

c02851d4a3a56ac652079724664e7de2224c6295

## Change Summary

Removed `SemanticResolver`'s source-level callable/body flow evaluator and made calls opaque to its bounded pre-typing ownership projection. Canonical typed summaries now carry source-site ownership requirements for aggregate mutation and every `@mut` argument, transfer them through captures, higher-order calls, and recursive SCCs, and let `SemanticFlowAnalyzer` preserve the existing `LYC-RESOLVE-022` diagnostic before graph publication. Resolver authority is explicitly dropped after calls and selected writes so it cannot reject from stale call or alias state.

Canonical summary branch/coalesce/short-circuit writes now preserve may-state when effects are conditional. Cross-module nilable aggregates retain a possible imported identity alongside route-specific nil provenance. Type checking defers only a mutable-argument qualifier mismatch needed to preserve established ownership-diagnostic precedence, restoring the original type error when canonical flow finds no ownership violation.

A follow-up repair closes the remaining metadata-dependent authority gap: callable, direct, and namespace-direct forms now invalidate resolver ownership-projection authority at call entry, before target or argument resolution, even when a predicate-bound target has no resolver type or signature. Signature lookup now supplies only provisional argument/result metadata. Focused positive and negative predicate-binding regressions prove that canonical typed flow accepts all-path local captured rebinding and still rejects a reachable imported path.

## Files

- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/SemanticResolver.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/SemanticFlowAnalyzer.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/SemanticFlowFactValidator.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/TypeChecker.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/OwnershipRequirement.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/CallableSummary.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/CallableSummaryCompiler.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/CallableSummarySolver.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/CallableSummarySet.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/SummaryTransferResult.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/SummaryLimits.java`
- `lyra-compiler/src/test/java/CallableSummaryTest.java`
- `lyra-compiler/src/test/java/Domain11AggregateOwnershipTest.java`
- `lyra-compiler/src/test/java/Domain11FlowStateTest.java`
- `lyra-compiler/src/test/java/Domain11SemanticTest.java`
- `.internal-dev/specifications/decisions.md`
- `.internal-dev/knowledge/semantic-aggregate-provenance.md`
- `.internal-dev/knowledge/typed-semantic-ir-validation.md`

## Behavioral Impact

Imported aggregate mutation remains rejected with the same code, phase, severity, target span, related import spans, and no partial typed graph. Direct source-local cases may still fail during resolution; call/capture/alias-wide cases are deferred to the one canonical typed producer. Call-target signature availability no longer controls that deferral. Legal local replacement, source-order callable replacement, lambda creation without body execution, known invocation, shared cells, exact/wildcard routes, and full-branch replacement remain accepted. Definitively non-callable callable/direct/namespace targets retain `LYC-TYPE-007`, and independently imported direct mutations retain early `LYC-RESOLVE-022` after preceding calls.

## Specification Impact

The source-language specification is unchanged. `specifications/decisions.md` now records the internal authority boundary: no resolver callable replay, bounded pre-typing projection, and canonical symbolic ownership requirements.

## Risks

The resolver retains a deliberately narrow aggregate-only projection for early diagnostics needed before some invalid programs can complete typing. Its authority flag must remain conservative: every future call-like or alias-wide operation must defer at operation entry rather than infer effects locally or depend on recovered metadata. `OwnershipRequirement` is compiler-internal phase data, not a serialized, runtime, or stable product API.

## Validation Evidence

- The predicate-bound captured-call regression reproduced the pre-repair false `LYC-RESOLVE-022` before production code changed, then passed after call-entry invalidation.
- Focused resolver/aggregate/call/summary/initialization suites: 109 tests passed.
- `mvn -pl lyra-compiler clean test`: 261 tests passed.
- `mvn clean verify`: reactor passed; compiler ran all 261 tests.
- `git diff --check`: passed.
- Scoped inspection found all three source call forms invalidating authority before target/argument traversal, one canonical `SemanticFlowAnalyzer` invocation, no resolver callable/capture/summary/effect replay, no placeholders or new production API, and no Phase-12 behavior.

## Follow-up Items

Obtain an independent read-only validator verdict focused on the removed adapter and authority boundary. Do not treat this changelog as a Phase-11 or Gate-11E pass declaration.
