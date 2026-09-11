# Domain 9 Typed Semantic Follow-up

## Date

2026-08-31

## Git Commit

c02851d4a3a56ac652079724664e7de2224c6295

## Change Summary

Repaired recursive expected-type flow for nested numeric operations without permitting nonliteral narrowing. Separated integer-division operand common typing from its operator-defined F32/F64 result context so exact zero checks remain compile-time diagnostics. Strengthened typed semantic provenance to reconstruct inferred conditional branch typing and to require an exact immutable predicate-binding contract.

## Files

- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/TypeChecker.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/TypedSemanticProvenance.java`
- `lyra-compiler/src/test/java/TypeCheckerTest.java`
- `.internal-dev/knowledge/typed-semantic-ir-validation.md`
- `.internal-dev/changelogs/2026-08-31-domain-9-typed-semantic-follow-up.md`

## Behavioral Impact

Nested exact arithmetic now adopts a legal I8/F32 or widening context recursively, including unary minus, while fixed I64 values still cannot narrow to I8. Integer division retains one common integer operand type and exact constant-zero validation before selecting F32 or F64. Typed graph publication rejects coherently retagged inferred conditionals and predicate bindings whose contract differs from the predicate's immutable narrowed type.

## Specification Impact

Specification Impact: none. The repair enforces the existing numeric, conditional, predicate-narrowing, immutable typed-graph, and exact-diagnostic contracts in `language-core.md` and `backend-runtime.md`.

## Risks

Contextual numeric checking may revisit a phase-local operand after context-free synthesis; only the selected immutable expression tree is published. Canonical conditional reconstruction intentionally mirrors current scalar numeric rules and must be extended with later aggregate semantics rather than weakened.

## Follow-up Items

- Extend canonical source-derived typing alongside later aggregate and backend domains.
