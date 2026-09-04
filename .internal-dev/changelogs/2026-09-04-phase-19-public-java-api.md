# Phase 19 Public Java Compiler and Runtime APIs

## Date

2026-09-04

## Git Commit

03395c1dad2ee11e62732143bdcd0e36e02c6d23

## Change Summary

Finalized the public immutable compiler and runtime loading boundary. The compiler now returns validated deterministic artifacts through structured compile results. Runtime loading supports path, class-directory, thin-JAR, and in-memory artifacts with strict compatibility preflight, parent-first runtime identity, exact export handles, and isolated module instances.

## Files

- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/api`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/{ArtifactSource,ExportHandle,LoadOptions,LoadedArtifact,LyraRuntime,ModuleHandle}.java`
- Phase-19 public API integration tests

## Behavioral Impact

Public records/builders defensively copy inputs and preserve structured compiler diagnostics. Runtime definition maps compatibility failures to `LYR-LINK` and verification failures to `LYR-VERIFY`, binds exact export method handles at definition time, preserves artifact/module ownership and lifecycle checks, authenticates Lyra closures, and prevents cross-instance state leakage.

## Specification Impact

Specification Impact: none. The API and loader implement the existing Phase-19 public Java compiler/runtime contract.

## Risks

Bundled executable loading remains fail-closed until the later launcher/CLI phase. Standard I/O, full conformance, performance gates, and release review remain later phases.

## Follow-up Items

Later launcher work must add executable bundled-JAR success coverage without weakening strict loader compatibility, ownership, or exact-handle boundaries.
