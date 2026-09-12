# Nominal construction authority progress

## Date

2026-09-12

## Git Commit

`f00ea111650ae36485578effbb468d2dcda8d4f2` (baseline).

## Change Summary

Add an object authority/initialization base, single-use receiver construction
capability and exact nominal value authentication for generated typed classes.
The runtime base stores no source values. Private primitive fixture fields exercise
initialization, mutation and lifetime rules with an independent seeded slot model.

## Files

LyraNominalObject, LyraNominalConstruction, LyraNominalSupport, LyraOwnershipToken,
NominalConstructionTest, extended fuzz selection, backend specification, nominal
knowledge/phase plan and language-testing coverage.

## Behavioral Impact

Construction is restricted to a schema-bearing declaring-module producer and one
exact final class/receiver. The representation checks its own embedded contract.
Partial receivers cannot pass ordinary access/authentication; constructor reads
require an initialized slot and the active ticket. Immutable slots initialize once;
mutable slots permit replacement. Completion/failure consumes the ticket and frees
initialization bit storage. Public access, generated access and value boundaries
retain producer thread/lifecycle, domain, exact type/schema and mutation checks.

## Specification Impact

Records the runtime construction protocol for the accepted final typed reference
representation. It supplements, not replaces, static initialization/privacy proofs.
Generated initialization must validate values before marking their slots and perform
the typed store immediately; exceptions invalidate partial construction.

## Validation

Focused construction tests, extended `tools/fuzz-language.sh -q`, and final
`mvn -q test` passed after value-boundary additions. Extended validation includes
3,600 independent initialized-slot/value cases. `git diff --check` passed. The
seven focused test methods have no failures or skips; source object emission and
release qualification are not claimed.

## Risks

Runtime fixtures are not emitted nominal source programs. Generated class inventory,
factory/accessor wiring, source-mapped failure handlers, exact defining-revision
linkage, method-slot handling, equality and sessions remain unfinished. Module-level
runtime private-access checks do not confer lexical class access; source proofs must
still reject external private access. Nominal session-domain paths require actual
retained-source integration tests before completion.

## Follow-up Items

Commit validated runtime primitives and wire generated typed classes/factories to
them. Complete the remaining semantic/heap and session work, then run all nominal
source integration and mandatory release gates. This is a WIP checkpoint.
