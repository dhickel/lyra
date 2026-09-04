# Phase 19 Public API Validation

## Date

2026-09-04

## Git Commit

69bb959037227078ea76d1a2cdb40d0b9e2f6ed5

## Change Summary

Independently validated and repaired the Phase 19 compiler/runtime API boundary. Final artifact metadata is embedded consistently in generated facades, semantic-option revisions survive assembly, reserved function invocation names remain callable, and runtime loader lifecycle/linkage checks are stricter. Focused tests now cover immutable artifacts, deterministic recompilation, path and in-memory loading, profile rejection, facade metadata parity, exact handles, reserved names, and an ordinary Java consumer.

## Files

- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/artifact/ArtifactAssembly.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/backend/jvm/JvmBytecodeArtifact.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/backend/jvm/JvmBytecodeEmitter.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/api/SourceInput.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/LyraRuntime.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/api/Phase19PublicApiTest.java`

## Behavioral Impact

Published class bytes now expose the same canonical metadata as the in-memory artifact and its metadata/JAR entries. Runtime loading validates exact export member descriptors, maps JVM linkage failures to `LYR-LINK`, and prevents a concurrent loaded-artifact close from racing an active module factory. In-memory source input rejects unpaired UTF-16 surrogates rather than silently replacing them during UTF-8 encoding.

## Specification Impact

Specification Impact: none. These changes repair implementation defects against the existing Phase 19 public API, facade, loader, lifecycle, and metadata contracts without adding Phase 20 or later behavior.

## Risks

Bundled-JAR success remains intentionally unavailable until the later runtime launcher phase supplies `LyraLauncher`; existing behavior remains fail-closed for that mode.

## Follow-up Items

When later launcher work lands, rerun the bundled-JAR and executable-JAR matrix. Continue using the public API consumer and metadata-parity tests for future facade/loader changes.
