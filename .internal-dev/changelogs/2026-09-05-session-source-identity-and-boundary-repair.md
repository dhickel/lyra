# Session Source Identity and Boundary Repair

## Date

2026-09-05

## Git Commit

86696c7598b46185c56e00c421dca8ef2392eca1

## Change Summary

Added an explicit session compiler `SourceId` to `SessionCompileRequest` so session-owned submission identities remain distinct from passive caller labels and URIs. `LyraCompiler` now uses that identity for source discovery, session root collision protection, diagnostic mapping, and source spans. Expanded compiler tests cover repeated origins, UTF-16 origin mapping, exact import collision spans, immutable request identity, and preservation of the truthful unsupported live-linkage boundary after an attempted partial implementation was repaired.

## Files

- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/api/SessionCompileRequest.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/api/LyraCompiler.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/api/SessionCompilerTest.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/LyraFailureCategory.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/LyraRuntimeException.java`
- `pom.xml`

## Behavioral Impact

Session compiler identities no longer collide when multiple submissions share a caller origin. Diagnostics retain the caller-facing URI/offset mapping while imports and compiler-owned source identities remain disjoint. Normal AOT output and authenticated runtime boundaries remain unchanged. Cross-submission live values are still explicitly unsupported and are not claimed complete.

## Specification Impact

Extends the REPL source-origin/session identity contract: compiler-owned submission identities are distinct from caller origin metadata, while display mapping remains passive and source mapped. No new language behavior is introduced.

## Validation

- `mvn -pl lyra-repl -am test` passed: runtime 15, compiler 508, REPL 47.
- `mvn test` passed: runtime 15, compiler 508, REPL 47, CLI 15.
- `git diff --check` passed.

## Risks

The persistent typed/live-value linker, console/JLine surfaces, and application attachment remain unfinished. The current session intentionally retains metadata only.

## Follow-up Items

Implement authenticated typed storage linkage and live result extraction before claiming persistent cross-submission evaluation. Keep the unsupported diagnostic boundary until the generated session entrypoint is proven end to end.
