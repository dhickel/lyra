# Nominal class plans progress

## Date

2026-09-12

## Git Commit

`34a0fa29165384f2d80be122a9ab56ad44c1ba13` (baseline).

## Change Summary

Plan final nominal classes with exact declaration-ordered schemas, private typed
fields, initialization/access members and constructor-to-instance factory signatures.
Add a dedicated nominal-field mapping position and explicit recursive nominal
linkage. Include these layouts in generated plan identity, class indexes and ABI
parity. Do not allocate module instance storage for type declaration names.

## Files

NominalClassLayout; generated class/member/dependency/type plans and planner;
JvmAbiParity, JvmMappingContext/ValuePosition; planner and ABI mapper tests;
backend specification, nominal knowledge/phase plan and coverage records.

## Behavioral Impact

All nominal descriptor targets now have class plans. Recursion remains explicit
without false ordering cycles. Private/public and mutable/immutable member contracts
determine which accessor plans exist; immutable fields still have initialization
accessors. Factory results are exact nominal references, not constructor Unit results.
Normal nonnil primitive fields remain unboxed; nullable fields are single references.

## Specification Impact

Records the distinct field position and explicit non-ordering nominal linkage
contract, retaining exact schema/member/source parity and no type-name instance slots.

## Validation

Focused planner/mapper tests passed, including empty/constructor fixtures. One
fixture initially omitted the required block around a constructor assignment;
the already-failed campaign was stopped, the fixture corrected, and focused tests
rerun successfully. Final `mvn -q test`, extended `tools/fuzz-language.sh -q`, and
`git diff --check` passed. The 30 planner tests have no failures/skips. Seeded models now
check independent field and runtime-boundary descriptors in addition to projection.

## Risks

These are generated shapes, not emitted nominal method bodies or executing factories.
Source construction/field lowering, authenticated value stores, public Java naming,
method invocation/replacement, equality, remaining semantic flow and persistent
sessions still require implementation. Existing valid-source unsupported emission
cannot be counted as a passing execution test. No release claim is made.

## Follow-up Items

Commit validated layouts, then emit nominal object methods and typed construction
factories with source-to-JVM tests. Complete all semantic/session and release gates.
