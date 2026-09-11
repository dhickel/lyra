# Gate 11E Final Provenance and Topology Repair

## Date

2026-09-02

## Git Commit

c02851d4a3a56ac652079724664e7de2224c6295

## Change Summary

Closed the two remaining implementation defects submitted for Gate 11E independent validation without changing source-language semantics or entering Phase 12.

`ResolvedTopologyValidator` now treats a module edge as the exact `(source module, logical target, target module, ImportPath.span)` record produced by source discovery. Source-header and edge multiplicity are compared with that span included, so a different valid source span, a sibling import span, a retargeted edge, or missing/duplicate/extra edge data cannot survive resolved-graph construction. Existing `ModuleGraph.Edge`, `ModuleGraph`, resolved-graph, and typed-graph record equality/hash paths already include the edge record; focused tests now exercise that behavior with forged production graphs.

The resolver-owned source-topology authority now freezes exact capture records and both capture-to-reference and reference-to-capture indexes alongside declarations, scopes, and references. Structural topology validation independently derives each required capture chain from the source reference's canonical scope, exact lexical target, and enclosing-lambda ancestry using the resolver's self-recursion and module-linked-function exclusions. It requires one exact `(lambda, declaration)` slot, exact lambda scope, capture mode/shared cell, declaration span, first causative source-reference span, source-ordered direct references, and reciprocal reference linkage. Empty direct-reference lists remain legal only for source-proven transitive captures. Typed provenance separately requires its reference index to reproduce the resolved capture index.

Manual semantic test graphs now derive edge spans from parsed import paths before construction. Adversarial sealing tests bypass that fixture helper and mutate real `ModuleGraph.Edge`, `ResolvedReference`, and `ResolvedCapture` records.

## Files

- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/ResolvedTopologyValidator.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/ResolvedReferenceTopology.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/SemanticResolver.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/TypedSemanticProvenance.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/source/ModuleGraph.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/semantic/Domain11SealingTest.java`
- `lyra-compiler/src/test/java/CanonicalModuleGraph.java`
- `lyra-compiler/src/test/java/CallableSummaryTest.java`
- `lyra-compiler/src/test/java/Domain11AggregateOwnershipTest.java`
- `lyra-compiler/src/test/java/Domain11FlowStateTest.java`
- `lyra-compiler/src/test/java/Domain11InitializationFlowTest.java`
- `lyra-compiler/src/test/java/Domain11SemanticTest.java`
- `lyra-compiler/src/test/java/ModuleGraphDiscoveryTest.java`
- `lyra-compiler/src/test/java/SemanticResolverTest.java`
- `lyra-compiler/src/test/java/TypeCheckerTest.java`
- `.internal-dev/specifications/decisions.md`
- `.internal-dev/knowledge/semantic-aggregate-provenance.md`
- `.internal-dev/knowledge/typed-semantic-ir-validation.md`

## Behavioral Impact

Source-language behavior is unchanged. Production resolved-graph construction now rejects mutated/missing/foreign/retargeted/duplicate/extra import-edge provenance and coordinated capture/reference forgeries before they can reach flow analysis or typed publication. Valid ordinary captures, repeated direct references, nested transitive captures, captured mutable cells, selective-import captures, module dependency planning, and existing Phase 11 flow behavior remain accepted.

Validation evidence:

- Focused sealing/topology/capture suite: 30 tests passed.
- Relevant semantic suites (`Domain11SealingTest`, `SemanticResolverTest`, `TypeCheckerTest`, `Domain11AggregateOwnershipTest`, `Domain11FlowStateTest`, `Domain11ContextualTypingTest`, `CallableSummaryTest`, `Domain11InitializationFlowTest`, and `Domain11SemanticTest`): 181 tests passed.
- `mvn -pl lyra-compiler clean test`: 247 tests passed.
- `mvn clean verify`: four-module reactor passed; 247 compiler tests passed.
- The original capture-forgery probe now fails during resolved topology construction with `source reference is missing its exact direct capture linkage`.
- A canonical-path variant of the original edge probe now rejects the mutated edge span with `module edges do not cover exactly every source import header`.
- `git diff --check` and the touched-file whitespace audit passed.

## Specification Impact

`specifications/decisions.md` now records exact import-path edge spans and complete producer-owned plus independently derived capture/reference topology. `language-core.md` and `backend-runtime.md` are unchanged because the repair enforces existing immutable phase-artifact and lexical-capture contracts without changing source semantics or adding backend/runtime behavior.

## Risks

The capture derivation is a bounded topology walk, not semantic-flow evaluation. It mirrors only resolver capture eligibility over already source-authoritative scopes, declarations, references, and function linkage; the canonical `SemanticFlowAnalyzer` publication call remains singular. Compilation-local `FlowSiteId` allocation, producer fact certification, graph equality/hash behavior, and the one package-owned `TypedSemanticGraph.seal` path are preserved.

The canonical edge span is specifically `ImportDeclaration.path().span()`, matching `ModuleGraphDiscovery`; the full declaration span remains the separate import-binding/header span. Intermediate lambdas may have an empty direct-reference list only when a descendant source reference derives that transitive capture.

The repository's pre-existing dirty, deleted, and untracked prototype/module work remains preserved.

## Follow-up Items

- Submit these two repaired findings for independent read-only validation.
- Do not declare Gate 11E passed until that validation completes.
- Do not begin Phase 12 until Gate 11E and the integrated Phase 11 gate receive the required independent verdicts.
