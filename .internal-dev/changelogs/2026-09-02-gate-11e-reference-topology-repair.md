# Gate 11E Reference Topology and Provenance Repair

## Date

2026-09-02

## Git Commit

c02851d4a3a56ac652079724664e7de2224c6295

## Change Summary

Closed the two remaining requested Gate 11E reference-topology defects. `SemanticResolver` now publishes an immutable compilation-local reference-topology authority containing its exact reference records and reciprocal declaration/scope indexes. The authority is object-bound to the one resolved graph produced by that resolver run; type checking and final typed-graph sealing reject reconstructed or uncertified resolved graphs.

`ResolvedTopologyValidator` now derives each reference's unique innermost source scope, validates its exact lambda owner, and applies the same shared lexical-selection law used by resolver production and flow provenance. That law walks lexical ancestors using Lyra's source-order replacement and function-predeclaration rules, and the validator requires the resulting declaration identity to match the resolved reference. Updated syntax links, same-name sibling declarations, and same-module/same-lambda-owner scopes are therefore not interchangeable.

Focused adversarial tests cover same-name retargeting with a matching forged syntax link and sibling-scope substitution under one lambda. Positive coverage preserves source-order shadowing, nested block lookup, selective and namespace imports, and captured lambda references.

## Files

- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/LexicalBindingLookup.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/ResolvedReferenceTopology.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/ResolvedSemanticGraph.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/ResolvedTopologyValidator.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/SemanticResolver.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/TypeChecker.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/TypedSemanticGraph.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/semantic/Domain11SealingTest.java`
- `.internal-dev/specifications/decisions.md`
- `.internal-dev/knowledge/semantic-aggregate-provenance.md`
- `.internal-dev/knowledge/typed-semantic-ir-validation.md`

## Behavioral Impact

Source-language behavior is unchanged. A resolved reference can publish only with the exact declaration, lexical scope, lambda owner, and reciprocal indexes selected by the canonical resolver. A same-name declaration that is later in source order or belongs to another scope/module cannot replace that target, even when its syntax link is forged consistently. A reference cannot be moved to another scope merely because the scope belongs to the same module and enclosing lambda.

Validation evidence: the focused sealing/resolution/type-checking command passed 66 tests; `mvn -pl lyra-compiler clean test` passed 244 tests; `mvn clean verify` passed the four-module reactor with 244 compiler tests; and both `git diff --check` and the edited-file whitespace audit passed.

## Specification Impact

`specifications/decisions.md` records the resolver-owned exact reference-topology authority and lexical/source-order validation rule. `language-core.md` and `backend-runtime.md` are unchanged because this repair enforces existing binding semantics and phase sealing without changing source behavior or beginning Phase 12.

## Risks

The new authority is package-owned, compilation-local, nonserialized, and excluded from graph equality/hash behavior. It does not rerun resolution or semantic flow, and the existing canonical flow analyzer still runs once. Package-owned producer code remains the trust boundary.

The separately reported missing `importSpan` field in `ResolvedTopologyValidator.HeaderEdge` and capture source-reference completeness finding remain deliberately out of scope and unresolved.

The repository's pre-existing dirty, deleted, and untracked prototype/module work remains preserved.

## Follow-up Items

- Obtain independent read-only Gate 11E validation before declaring the gate passed.
- Address the separate `HeaderEdge.importSpan` and capture source-reference completeness findings only under their own authorized scope.
- Do not begin Phase 12 until Gate 11E and the integrated Phase 11 gate receive independent PASS verdicts.
