# Domain 9 Typed IR Validator Repairs

## Date

2026-08-31

## Git Commit

c02851d4a3a56ac652079724664e7de2224c6295

## Change Summary

Repaired typed unary-minus and explicit-conversion qualifier coercion, prevented nil literals from inventing mutation permission, and made nil equality common-type every non-nil operand. Added exact post-common-type constant integer evaluation for checked arithmetic, recursive syntax-to-typed-graph provenance and closed publication indexes, and exact typed-expression-to-IR correspondence. Tightened closed-IR validation for source spans/scopes, exact numeric constants, short-circuit representation, integer-division operand widening, adjacent conversion edges, predicate bindings, call/access shape matrices, built-in member contracts, nil-compatible function equality, and runtime-check categories. Added focused regressions for every reported validator finding.

## Files

- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/TypeChecker.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/TypedSemanticGraph.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/TypedSemanticProvenance.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/TypedIrBuilder.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrValidator.java`
- `lyra-compiler/src/test/java/TypeCheckerTest.java`
- `.internal-dev/knowledge/typed-semantic-ir-validation.md`
- `.internal-dev/changelogs/2026-08-31-domain-9-typed-ir-validator-repairs.md`

## Behavioral Impact

Mutable numeric unary-minus and explicit-conversion operands now carry an explicit mutability-drop conversion. `#NIL` can satisfy nilability but cannot satisfy `@mut`, and variadic nil equality selects one lossless common type across all non-nil numeric operands before nil lifting. Known-invalid constant integer arithmetic now fails with `LYC-TYPE-008` before graph publication while nonconstant arithmetic retains runtime checks. Typed publication rejects same-span source-node substitutions and inconsistent expression indexes. IR validation rejects non-corresponding nodes, broadened spans, wrong block scopes, illegal member receivers/results, and malformed tuple indices, while allowing function value equality against explicit `#NIL` under one nilable function contract. Eager `and`/`or`, malformed integer division, skipped or mislabeled conversion edges, incompatible access/call shapes, malformed constants/operators, unrelated predicate bindings, and incorrectly categorized runtime checks continue to produce structured LYC-IR diagnostics.

## Specification Impact

Specification Impact: none. The changes enforce the existing `language-core.md` typing rules and `backend-runtime.md` closed typed-IR invariant without changing either contract.

## Risks

The predicate-binding proof intentionally targets the initial conditional-branch IR shape. Exact IR correspondence intentionally shares the canonical lowering implementation, so every future typed expression or synthetic IR wrapper must extend lowering and provenance validation together. Member direct calls and aggregate construction remain outside the initial typed subset. Later control-flow, aggregate, or member-bearing type expansion must extend the source-provenance and access matrices rather than weaken them.

## Follow-up Items

- Extend these invariants alongside later aggregate IR nodes rather than weakening scalar validation.
