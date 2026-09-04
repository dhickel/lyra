# Phase 15 Scalar and Control Bytecode Emission

## Date

2026-09-03

## Git Commit

1c9365c487145d8d127879270397fecd57023666

## Change Summary

Implemented and validated the Phase-15 scalar/control JVM emission slice through the Java 25 Class-File API. The production emitter consumes validated typed IR and immutable JVM plans, emits deterministic verified classes for scalar/control operations, preserves strict evaluation and source lines, maps structured runtime failures to authoritative IR sites, and lowers supported direct self-tail calls to loops. The generated module-state plan also exposes the narrow lifecycle composition/linkage shape required by later module execution without implementing module bodies.

## Files

- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/backend/jvm/JvmBytecodeArtifact.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/backend/jvm/JvmBytecodeEmitter.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/backend/jvm/JvmEmissionException.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/backend/jvm/GeneratedClassPlan.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/backend/jvm/GeneratedMemberKind.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/backend/jvm/GeneratedMemberPlan.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/backend/jvm/GeneratedTypePlanner.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/backend/jvm/JvmAbiParity.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/backend/jvm/GeneratedTypePlannerTest.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/backend/jvm/Phase15SmokeTest.java`

## Behavioral Impact

The compiler now has a production, planning-to-class-file scalar/control path for the supported Phase-15 IR slice, including checked arithmetic/conversions, branching, short-circuiting, coalescing, calls, source line attributes, and self-tail loops. Unsupported aggregate/module/closure behavior is rejected explicitly and remains deferred to later phases. Narrow state lifecycle members are planned for later composition, while `ModuleLifecycle` remains final and unmodified.

## Specification Impact

Specification Impact: none. This implements the existing Phase-15 backend contract without changing source semantics or adding deferred interop, packaging, runtime loading, CLI, or public API behavior.

## Risks

Aggregate, closure, module, packaging, loading, facade, CLI, standard-I/O, and public Java API behavior remains intentionally incomplete and must be implemented in later phases. The scalar emitter must remain behind validated IR and the immutable ABI planner as those phases expand coverage.

## Follow-up Items

Phase 16 will complete aggregate, function, and module bytecode while preserving the direct Class-File API emission boundary and explicit unsupported-node checks.
