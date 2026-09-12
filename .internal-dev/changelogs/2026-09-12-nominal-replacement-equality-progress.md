# Nominal replacement and equality progress

## Date

2026-09-12

## Git Commit

`c7bac45778608170ed8d0894b62b5b463494c7dd` (baseline).

## Change Summary

Implement contextual `self` capture for directly assigned method lambdas and
cycle-safe typed struct equality while retaining class identity semantics.

## Files

Semantic resolution/topology/provenance, type and IR validation, JVM nominal member
planning/emission, runtime nominal/equality support, nominal semantic/bytecode/planner
tests, language/backend specifications, testing matrix, plan, knowledge and review.

## Behavioral Impact

A direct replacement lambda receives the selected instance as `self`, including in
nested closures, without receiving private lexical authority. Existing callables
retain their original receiver. Structs compare current field values structurally
with terminating cyclic traversal; nested classes compare by identity. Classes now
participate in identity operators, while structs do not.

## Specification Impact

Updates implementation status and records the accepted equality/receiver rules in
backend detail; it does not change the previously accepted language contract.

## Validation

Focused nominal semantic, bytecode and generated-plan tests; full `mvn -q test`;
extended `tools/fuzz-language.sh -q`; and `git diff --check` pass.

## Risks

Nominal artifact/Java construction surfaces and persistent REPL/session integration
remain incomplete, so this is not full structs/classes completion.

## Follow-up Items

Validate exact loader/package inventories and direct Java use, then implement
nominal session persistence, snapshots, reload and lifetime behavior.
