# Nominal factory transfer progress

## Date

2026-09-11

## Git Commit

`d5f3ce52e242d83ca7c72b2c352960d2730d68e0` (baseline).

## Change Summary

Added exact nominal constructor call references and caller-owned heap transfer for
factory functions. Ordered summary event application preserves writes around
construction. Activation-local memoization prevents formula projections from
replaying constructor calls. Transitive factories remain caller-dependent in the
solver instead of requiring a nonexistent compile-time heap.

## Files

Callable summary compiler/solver/transfer records, semantic flow analyzer and sealer,
nominal semantic tests, coverage documentation and active implementation records.

## Behavioral Impact

Source-to-typed/IR tests cover factories returning structs and initialized class
methods, wrapper factories, discarded construction results, writes before and after
construction, and post-construction callable reads. Independent seeded slot models
vary write placement and wrapper calls. Module-root mutable function slots receive
the same linked-storage write transfer outside sessions as within them.

## Specification Impact

None: this implements part of the accepted nominal semantics without changing
syntax, representation, visibility or lifetime contracts.

## Validation

Focused tests, the extended `tools/fuzz-language.sh -q` campaign and the final
ordinary `mvn -q test` reactor run passed. Extended validation includes 5,400
factory-slot cases and the existing language/runtime campaigns. The final ordinary
run also includes exact constructor-formula negative tests added after the extended
run. `git diff --check` passed. No JVM execution or release-audit claim is made.

## Risks

This remains a WIP feature. Branch-sensitive and repeated constructor effects,
recursive construction, contextual replacement self, complete heap ownership and
producer validation still need work. JVM emission, authenticated runtime schemas,
object authority/equality, artifact and retained-session support are not implemented
by the compiler-only heap. No unsupported backend result is accepted as execution.

## Follow-up Items

Continue directly with remaining semantic work and exact backend/runtime support;
keep the full feature goal active. Finish source-to-JVM/session qualification and
the release gate before declaring classes and structs finalized.
