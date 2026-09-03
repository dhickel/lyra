# Phase 14 JVM ABI and Generated-Type Planner

## Date

2026-09-03

## Git Commit

6110615f7617af8dc9f37f06225f5df3285bb4e9

## Change Summary

Independently validated and repaired the immutable Phase-14 JVM ABI mapper and deterministic generated-type planner. The implementation now rejects malformed descriptors, inconsistent canonical/type metadata, invalid nullable/Unit representations, incomplete generated class/member shapes, missing export provenance, absent generated dependencies, and incomplete cross-module recursive-link shapes before any later emission phase. Closure signature references are represented as explicit ordering dependencies, while recursive linkage remains non-ordering. Module-state imports use explicit post-construction link members so legal function SCC shells can be linked without conflating class ordering with eager initialization.

## Files

- `pom.xml` (Java-25-capable Maven Dependency Plugin configuration)
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/backend/jvm/`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/identity/JavaNameMangler.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/backend/jvm/`
- `.internal-dev/reviews/2026-09-03-phase-14-independent-validation.md`
- `.internal-dev/knowledge/java-25-build-validation.md`

## Behavioral Impact

ABI planning remains immutable and planning-only. It preserves exact primitive/reference/wrapper/Unit/array/tuple/function mappings, stable Java names, export metadata, generated class dependencies, and deterministic topological class order. No bytecode, runtime, CLI, or Phase-15 behavior was added or changed.

## Specification Impact

Specification Impact: none. The changes enforce the existing `backend-runtime.md` JVM ABI and generated-class contracts without changing language or backend semantics.

## Risks

The dependency tree and `dependency:analyze` checks now run with Maven Dependency Plugin 3.9.0, which supports Java 25 class-file major version 69. The analyzer reports only test-scope/empty-CLI modeling warnings; runtime/compiler dependency boundaries and `jdeps` remain clean. Existing unrelated worktree deletions, untracked files, and POM changes were preserved.

## Follow-up Items

No Phase-14 implementation follow-up remains. Later emission must consume the immutable plans and implement the planned post-construction state import-link members.
