# Phase 22 Conformance Validation and Repair

## Date

2026-09-04

## Git Commit

b517e46ebc87b2c1b513581a02d283959cf9a3a5

## Change Summary

Completed independent Phase 22 conformance and integration validation. Repaired debug-origin resolution for generated intrinsic `std->io` closure adapters and added a repeated-compilation byte-identity assertion to the Phase 22 matrix.

## Files

- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/backend/jvm/JvmBytecodeArtifact.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/api/Phase22ConformanceTest.java`
- `.internal-dev/reviews/2026-09-04-phase-22-independent-validation.md`

## Behavioral Impact

Intrinsic function adapter methods now receive their owning intrinsic declaration's source span and function name when debug metadata is assembled. This prevents valid intrinsic artifacts from failing emission-time source-map construction. Identical source/options compilations are explicitly checked for byte-identical metadata, class files, and entries.

## Specification Impact

None. The repair implements the existing Phase 22 and backend-runtime source-map contract; no language, runtime, CLI, or artifact contract was changed.

## Risks

The Java 25/Linux path was exercised. Native Windows execution and performance/allocation thresholds were not part of Phase 22 and remain later-phase work. Maven reports only existing `ThreadDeath` deprecation and shade manifest-overlap warnings.

## Follow-up Items

Phase 23 owns performance/allocation evidence. Phase 24 owns final release-scope auditing and the complete requirement matrix.
