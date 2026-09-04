# Phase 18 Artifact Metadata and Deterministic Packaging

## Date

2026-09-04

## Git Commit

677472f4fba168da286d2a0ed257edc13f69f611

## Change Summary

Implemented the internal Phase-18 artifact boundary: deeply immutable validated assemblies, schema-1 canonical artifact and debug-map metadata, source snapshots and hashes, class-file validation, deterministic class-directory and thin-JAR output, bundled fail-closed validation, canonical manifests, and atomic staged publication.

## Files

- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/artifact`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/backend/jvm/JvmBytecodeArtifact.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/backend/jvm/JvmBytecodeEmitter.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/ArtifactMetadata.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/ArtifactMetadataReader.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/SourceMetadata.java`
- Phase-18 artifact and packaging tests

## Behavioral Impact

Artifact metadata preserves complete export contracts, source-kind identity, revisions, compatibility/profile requirements, debug BCI/source mappings, and optional source content. Class directories and thin JARs use stable ordering, STORED ZIP entries, DOS epoch timestamps, no ZIP extras/comments, and deterministic bytes. Atomic publication refuses unsafe or conflicting outputs and preserves an existing target when assembly fails. Bundled output rejects absent later launcher/runtime content rather than publishing an incomplete executable.

## Specification Impact

Specification Impact: none. The implementation follows the existing Phase-18 artifact, metadata, source-map, compatibility, and deterministic-publication contract.

## Risks

Bundled success and executable-JAR launch remain unavailable until the later launcher/CLI phase. Public compiler/load/instantiate APIs, standard I/O, conformance, performance, and release work remain later phases.

## Follow-up Items

When the launcher phase lands, add bundled success-path and executable-JAR subprocess coverage while retaining the fail-closed incomplete-bundle checks.
