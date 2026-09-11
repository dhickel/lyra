# Gate 11E Provenance and Sealing Architecture Repair

## Date

2026-09-02

## Git Commit

c02851d4a3a56ac652079724664e7de2224c6295

## Change Summary

Replaced post-hoc semantic-flow reconstruction at sealing with a producer-certified provenance boundary. The canonical `SemanticFlowAnalyzer` now returns a flow-certified immutable `TypedSemanticCore` and exact immutable expected `SemanticFlowFacts`; the package-owned graph sealer accepts only that core and facts equal to the producer record. The remaining fact validator audits explicit site/route/schema coverage and reciprocal topology without replaying value flow, call transfer, captures, allocation ordering, or eager effects.

Added deterministic compilation-local `FlowSiteId` ownership for typed expressions, references, and captures. Events, summary calls, callable values, effect sites/paths, route-specific nil alternatives, and aggregate allocation origins retain producer-issued provenance. Ownership records now separate canonical allocation origin from later use spans. Scope/lambda/parameter/capture/reference/mutation membership is checked bidirectionally, and synthetic missing-owner `ScopeId(0)` fallbacks were removed.

A follow-up repair binds certification to the exact returned and analyzed `TypedSemanticCore` object rather than structural content. It also adds one package-private resolved-topology audit rooted in source import headers, exact header-edge multiplicity, and the canonical logical-module map; closes declaration/initializer-lambda ownership in both directions; and binds every mutation to the source assignment's exact root `ReferenceId` across resolved, typed, and syntax-link indexes.

## Files

- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/identity/FlowSiteId.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/TypedSemanticInput.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/TypedSemanticCore.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/TypedSemanticGraph.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/TypeChecker.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/SemanticFlowAnalyzer.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/SemanticFlowFactValidator.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/SemanticResolver.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/ResolvedSemanticGraph.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/ResolvedTopologyValidator.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/ScopeTree.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/CallableCallReference.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/CallableFlow.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/CallableSummaryCompiler.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/CallableSummarySet.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/CallableSummarySolver.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/EagerEffectWitness.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/FormulaAlternatives.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/NilProvenance.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/OwnershipWitness.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/SemanticFlowEvent.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/SemanticFlowFacts.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/ValueAlternative.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/ValueAlternatives.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/ValueFormula.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/semantic/Domain11SealingTest.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/semantic/SemanticTestSupport.java`
- `lyra-compiler/src/test/java/Domain11ContextualTypingTest.java`
- `lyra-compiler/src/test/java/Domain11SemanticTest.java`
- `lyra-compiler/src/test/java/TypeCheckerTest.java`
- `.internal-dev/specifications/decisions.md`
- `.internal-dev/knowledge/semantic-aggregate-provenance.md`
- `.internal-dev/knowledge/typed-semantic-ir-validation.md`

## Behavioral Impact

Source-language behavior is unchanged. Canonical flow publication now rejects any missing, extra, foreign, wrong-route, wrong-target, wrong-capture, wrong-origin, duplicate, or mutually forged fact set before plan validation. Route-specific nil survives aggregate composition, summary transfer, and contextual conversion; root nil alternatives are removed by nil-only coalescing. Initialization planning remains facts-only, and normal type checking still invokes the canonical evaluator exactly once.

Focused sealing coverage now includes route-specific sibling nil, same-typed nested capture substitution, missing/duplicate/forged effects, callable effect-target substitution, duplicate/extra effect paths, canonical allocation origins across aliases, cross-lambda parameter reownership, equal-core certificate reuse, missing header edges, retargeted and missing import bindings, forged re-export origins, absent/foreign/multiple initializer-lambda owners, and same-target mutation-reference substitution. The cyclic two-module semantic fixture now derives its complete edge list from both source headers. Existing Gate 11A through 11D.2 suites remain green.

## Specification Impact

`specifications/decisions.md` records the accepted producer-certified provenance authority and compilation-local site-identity model. `language-core.md` and `backend-runtime.md` require no source-visible change; the repair remains internal Phase 11 work.

Validation evidence for the follow-up repair: the focused sealing/resolution/typing/11A–11D.2 command passed 174 tests; `mvn -pl lyra-compiler clean test` passed 240 tests; `mvn clean verify` passed the four-module reactor with 240 compiler tests; and both `git diff --check` and the edited-file whitespace check passed.

## Risks

The certification authority trusts the canonical analyzer as the semantic producer; this is deliberate and avoids a forbidden second evaluator. Its private one-shot construction state is completed before publication, exposes no mutator, and validates the exact core with Java object identity. Analyzer omissions remain analyzer defects and must be caught by the 11A–11D.2 semantic matrix. `FlowSiteId` and certification records are internal, compilation-local, nonserialized, and not compatibility APIs.

The repository's pre-existing dirty, deleted, and untracked module/prototype work remains preserved.

## Follow-up Items

- Obtain independent read-only Gate 11E validation before claiming the gate passed.
- Do not begin Phase 12 until Gate 11E and the integrated Phase 11 gate receive independent PASS verdicts.
