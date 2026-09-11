# Domain 9 Inferred Root Provenance Repair

## Date

2026-08-31

## Git Commit

c02851d4a3a56ac652079724664e7de2224c6295

## Change Summary

Closed the remaining inferred-declaration provenance loophole. Typed semantic publication now treats only an explicit declared contract as an initializer context, independently synthesizes unannotated initializers without context, normalizes only binding-local mutation and nilability, and requires the inferred contract and published initializer to equal that derived type. Added a regression that forges an `I8 -> I16` root conversion and matching `I16` contract around `let x = (+ 1I8 2I8)` and requires graph construction to reject it.

## Files

- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/TypedSemanticProvenance.java`
- `lyra-compiler/src/test/java/TypeCheckerTest.java`
- `.internal-dev/knowledge/typed-semantic-ir-validation.md`
- `.internal-dev/changelogs/2026-08-31-domain-9-inferred-root-provenance.md`

## Behavioral Impact

An inferred declaration can no longer authorize a forged root numeric widening by changing its own published contract. Declared expected contracts still authorize contextual widening, nested inferred common-type widening remains valid, and explicit source conversions retain their target type during context-free synthesis. Invalid typed artifacts continue to fail at the immutable typed-graph publication boundary before IR lowering.

## Specification Impact

Specification Impact: none. The repair enforces the existing context-sensitive numeric typing and complete immutable semantic-phase output contracts in `language-core.md` and `backend-runtime.md`.

## Risks

Context-free provenance intentionally mirrors the type checker's source synthesis rules. Future expression forms must extend both paths together. No runtime, backend, aggregate, or public API behavior changed.

## Follow-up Items

- None for this repair.

## Validation

- `mvn -pl lyra-compiler test`: passed, 94 tests.
- `mvn clean verify`: passed for all four reactor projects, including 94 compiler tests.
- `git diff --check`: passed.
