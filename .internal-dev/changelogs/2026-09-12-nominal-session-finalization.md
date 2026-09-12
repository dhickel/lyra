# Nominal session finalization

## Date

2026-09-12

## Git Commit

`e5014a2ffda45a76ff98f5a009a5065d1c7bb4fa` (baseline).

## Change Summary

Complete persistent struct/class semantics through session compilation, exact JVM
type sharing, producer-bound retained construction and bounded nominal snapshots.

## Files

Compiler session certificates/resolution/flow/IR emission; runtime type loading,
storage capabilities and module lifecycle; standalone/attached REPL linkage and
snapshots; nominal session tests; specifications, plans, testing docs, knowledge and
self-review.

## Behavioral Impact

Later submissions retain exact nominal names and schemas, original object/member
state, saved and replaced methods, nested callable proofs and exact executable
constructors. Struct/class snapshots distinguish their kind, show public members in
declaration order and exclude private class state.

## Specification Impact

The REPL and backend specifications now describe implemented nominal type sharing,
factory capabilities and snapshot behavior rather than an in-progress acceptance
target.

## Risks

Nominal classes use deferred JVM verification because standalone Class-File API
verification cannot resolve a class's self-typed descriptors before staged loading.
Inventory checks and actual JVM loading remain fail-closed.

## Validation

Focused `NominalSessionTest` and `ApplicationAttachmentTest`, full `mvn test`,
extended `tools/fuzz-language.sh -q`, `git diff --check`, and the mandatory
`tools/phase24-release-audit.sh` pass. The audit reports 157 non-deferred rows:
156 PASS and one Linux-host Windows-launcher N/A, with no blocked row.

## Follow-up Items

Graphical editor UI checks and native Windows execution remain environment-specific
qualification outside this Linux semantic/backend finalization.
