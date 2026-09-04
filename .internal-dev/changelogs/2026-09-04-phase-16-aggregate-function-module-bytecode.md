# Phase 16 Aggregate, Function, and Module Bytecode

## Date

2026-09-04

## Git Commit

348238ca15f115c4cfeeb893269bb6dd5ca52bb3

## Change Summary

Completed and independently checked the Phase-16 direct Java 25 Class-File API extension for aggregate, function, and module bytecode. The emitter now covers strings/chars and deterministic scalar conversion, arrays and tuple values, closures/cells/captures, recursive function linkage, imports and module state, eager initialization ordering, and exact failure/ownership boundaries while retaining the validated-IR gate and deterministic output.

## Files

- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/backend/jvm/`
  - `JvmBytecodeEmitter.java`
  - `GeneratedClassPlan.java`
  - `GeneratedMemberPlan.java`
  - `GeneratedTypePlanner.java`
  - `JvmAbiParity.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/backend/jvm/`
  - `GeneratedTypePlannerTest.java`
  - `Phase15SmokeTest.java`
  - `Phase16SmokeTest.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/`
  - closure ownership/lifecycle support and `LyraArtifactKey`

## Behavioral Impact

The production emitter now supports the Phase-16 aggregate/function/module execution slice with exact ABI representations, strict left-to-right evaluation, live array identity and mutation behavior, immutable tuple values, closure/cell linkage, legal recursion, module-state imports, eager schedule enforcement, and source-mapped runtime failures. Invalid eager cycles and unsupported later-phase behavior are rejected before publication.

## Specification Impact

Specification Impact: none. This fills the existing Phase-16 backend-runtime contract without changing language semantics or introducing Phase-17 lifecycle facades, packaging, public APIs, CLI behavior, or standard-library interop.

## Risks

Later phases must integrate the emitted module-state and closure linkage with generated facade lifecycle, artifact packaging, loading, public Java APIs, CLI, and standard I/O without bypassing the validated IR, ABI plans, or runtime ownership checks.

## Follow-up Items

Phase 17 will implement generated facades and complete module lifecycle behavior on top of these internal state and closure boundaries.
