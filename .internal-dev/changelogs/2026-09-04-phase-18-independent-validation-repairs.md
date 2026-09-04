# Phase 18 Independent Validation Repairs

## Date

2026-09-04

## Git Commit

caf9927078a4b24672635f1e66a75740174313c5

## Change Summary

Independent Phase 18 validation repaired three conformance defects: complete nullable-function export contracts are preserved in metadata, JAR manifest continuation lines obey the 72-byte limit, and path/URI source identities with equal values round-trip without collision. Added regression coverage for each case.

## Files

- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/backend/jvm/JvmBytecodeArtifact.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/backend/jvm/JvmBytecodeEmitter.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/artifact/ArtifactOutputWriter.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/ArtifactMetadataReader.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/artifact/Phase18ArtifactTest.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/backend/jvm/Phase18SmokeTest.java`

## Behavioral Impact

Published metadata now retains top-level `@nil` on function exports. Long manifest values wrap canonically. Metadata readers distinguish path and URI source identities when their value strings coincide. No source-language behavior changed.

## Specification Impact

Specification Impact: none. These are repairs to implementation conformance with the existing Phase 18 artifact, metadata, and JAR contracts.

## Risks

Bundled JAR success remains unavailable until the later runtime launcher/CLI work supplies `LyraLauncher`; the assembler rejects that incomplete mode rather than publishing a partial artifact.

## Follow-up Items

Re-run bundled success-path, launcher, and executable-JAR checks when the later launcher is implemented.
