# Nominal signature linkage progress

## Date

2026-09-12

## Git Commit

`f54eb8e095ff08acd539ae4a72ef47207b7c8777` (baseline).

## Change Summary

Carry exact nominal schemas on producer artifact keys and resolve generated
nominal-bearing callable signatures through the current producer authority.
Cache immutable parsed signatures per artifact without bypassing owner/lifecycle
checks. Accept schema 2 at the RuntimeOptions compatibility gate as well as the
metadata reader. Preserve the schema-free emission path for legacy signatures.

## Files

JvmBytecodeEmitter; runtime artifact key, ownership token, closure authority,
ModuleLifecycle, LyraRuntime and constants; NominalArtifactMetadataTest; backend
specification, nominal knowledge/plan and language coverage records.

## Behavioral Impact

Loaded artifacts and direct facade factories carry the same exact schema context.
Initializing producers may resolve signatures; failed, closed and wrong-thread
producers reject even cached lookups. A producer cannot reuse a key with different
schema contracts. Signature resolution is not live-object authentication.

## Specification Impact

Clarifies the existing schema-scoped signature requirement: metadata may be cached
at artifact scope, but every lookup retains the requesting producer's lifecycle.

## Validation

Focused metadata tests passed after correcting the remaining schema compatibility
gate. Full `mvn -q test`, extended `tools/fuzz-language.sh -q`, and
`git diff --check` passed. The extended campaign includes 3,600 independent
shared-producer lifetime cases. The first model compilation needed an explicit
exception-class variable for Java generic inference; no assertion was weakened.

## Risks

This is unfinished nominal support. Nominal object emission, factories/accessors,
equality, retained sessions and remaining semantic heap transfers are still open.
The nominal-specific emitter signature branch requires source-to-JVM qualification
once generated object classes are available. No unsupported positive is accepted.

## Follow-up Items

Finish validation and commit this linkage unit, then continue exact generated
object classes and source-executed construction. Full finalization and the release
audit remain required; this checkpoint is not completion.
