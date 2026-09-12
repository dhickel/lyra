# Nominal source factories progress

## Date

2026-09-12

## Git Commit

`08b9c91d9f833442693c8dba8bf4499c4ff91622` (baseline).

## Change Summary

Emit exact module-state factories for nominal declarations and lower Lyra
construction, field access/mutation and receiver-bound method invocation to direct
typed bytecode. Preserve once-only left-to-right arguments, initialization order,
saved-slot behavior, producer authority and failure cleanup. Route namespace and
selectively imported type roles to their defining factory.

## Files

Generated JVM member/class planning and ABI parity; bytecode emitter; semantic
resolver/flow reconciliation; nominal bytecode/planner tests; nominal language,
backend, testing, plan and knowledge records.

## Behavioral Impact

Struct and class instances can now be created by source code. Required struct fields
are installed before defaults, class defaults precede constructors, and failed
factories invalidate their construction ticket without hiding the original runtime
failure. Source reads, mutable writes, current method-slot calls and saved callable
references execute against exact generated fields. Type aliases are never emitted
as closure values.

## Specification Impact

Updates implementation status only; the accepted syntax and semantics are unchanged.

## Validation

Focused nominal source/factory/planner/semantic tests, full `mvn -q test`, extended
`tools/fuzz-language.sh -q`, and `git diff --check` pass. The extended campaign
drives 1,800 seeded cases per nominal bytecode seed through compiled Lyra factories.

## Risks

Contextual `self` for replacement lambdas, structural equality, complete public Java
construction/packaging coverage, loader inventory checks and persistent sessions are
still open. This progress unit is not full nominal completion.

## Follow-up Items

Commit after full validation, then implement contextual method replacement and
equality before completing artifact and session integration.
