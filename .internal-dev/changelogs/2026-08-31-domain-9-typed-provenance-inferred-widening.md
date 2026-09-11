# Domain 9 Typed Provenance Inferred Widening Repair

## Date

2026-08-31

## Git Commit

c02851d4a3a56ac652079724664e7de2224c6295

## Change Summary

Repaired recursive typed provenance so parent-selected widening of an inferred nonliteral expression is not mistaken for contextual checking of that expression's source subtree. Preserved true contextual typing for externally checked numeric operations and direct numeric or nil literals, including integer division's separate result context. Added typed-graph and closed-IR regressions for inferred arithmetic, comparison, division, and conditional branch widening.

## Files

- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/TypedSemanticProvenance.java`
- `lyra-compiler/src/test/java/TypeCheckerTest.java`
- `.internal-dev/knowledge/typed-semantic-ir-validation.md`
- `.internal-dev/changelogs/2026-08-31-domain-9-typed-provenance-inferred-widening.md`

## Behavioral Impact

Valid inferred expressions such as `let x = (+ (+ 1I8 2I8) 3I16)` now publish an immutable typed graph with the nested operation retained as I8 and one exact I8-to-I16 conversion at the parent boundary. The same provenance rule now accepts canonical relational, integer-division, and inferred-conditional widening while continuing to reconstruct unwrapped source subtrees without circularly trusting their widened result.

## Specification Impact

Specification Impact: none. The repair enforces the existing lossless numeric widening, contextual typing, immutable phase output, and closed typed-IR contracts in `language-core.md` and `backend-runtime.md`.

## Risks

The provenance validator intentionally mirrors the type checker's current points of contextual checking. Future operators or branch-unification strategies must extend both paths together rather than treating every final conversion target as a source context.

## Follow-up Items

- None for the repaired domain-9 scope.
