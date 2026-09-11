# Gate 11E — Typed Semantic Graph Sealing

> **Correction (2026-09-02):** Later independent adversarial validation found this implementation did not prove exact provenance and Gate 11E remained blocked. The producer-certified repair and current evidence are recorded in `2026-09-02-gate-11e-provenance-sealing-repair.md`; the entry below is retained as historical implementation context, not a PASS verdict.

## Date

2026-09-01

## Git Commit

c02851d4a3a56ac652079724664e7de2224c6295

## Change Summary

Completed the accepted Phase 11E sealing boundary. Type checking now freezes a package-owned typed semantic core, runs canonical typed flow/eager evaluation once, derives initialization from the resulting facts, and publishes the final graph through one package-owned validated seal. The final graph exposes immutable SemanticFlowFacts read-only, includes facts in equality/hash, and validates topology, provenance, fact coverage/invariants, and planner-derived initialization without rerunning the evaluator.

The former bootstrap empty-plan graph, public broad graph constructor, reconstruction method, and planner-side evaluator handoff were removed. Internal flow input support keeps the pre-seal core separate from the published graph. Focused sealing tests cover immutable nested facts, deterministic reconstruction/equality, access restrictions, forged/missing/foreign/wrong-route facts, plan rejection, and the single evaluator count.

## Files

- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/TypedSemanticCore.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/TypedSemanticInput.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/SemanticFlowFactValidator.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/TypedSemanticGraph.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/TypeChecker.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/SemanticFlowAnalyzer.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/InitializationAnalyzer.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/CallableSummaryCompiler.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/semantic/Domain11SealingTest.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/semantic/SemanticTestSupport.java`
- `lyra-compiler/src/test/java/TypeCheckerTest.java`
- `lyra-compiler/src/test/java/Domain11ContextualTypingTest.java`
- `lyra-compiler/src/test/java/Domain11SemanticTest.java`

## Behavioral Impact

Successful typed graphs carry the canonical immutable flow facts and the exact initialization plan that was derived from them. Failed or forged graph inputs do not publish a typed artifact. Existing semantic and typed-IR consumers continue to consume the published graph at their existing boundary; accidental public graph reconstruction is no longer supported.

## Specification Impact

Specification Impact: none. This implements the accepted Gate 11E sealing contract without changing source semantics or entering Phase 12; living specifications and knowledge records were intentionally not modified.

Validation evidence: `Domain11SealingTest` passed 5 tests; the clean compiler test passed 222 tests; the full reactor `clean verify` passed; and `git diff --check` passed.

## Risks

The flow model remains an internal JVM-independent compiler representation. The repository still contains the pre-existing dirty/deleted/untracked prototype and module work; it was preserved. No Phase 12 backend/runtime/CLI behavior was added.

## Follow-up Items

Proceed only to the accepted integrated Phase 11 validation gate after independent review; do not begin Phase 12 from this gate alone.
