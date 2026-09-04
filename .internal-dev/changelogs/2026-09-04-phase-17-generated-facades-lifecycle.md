# Phase 17 Generated Facades and Module Lifecycle

## Date

2026-09-04

## Git Commit

9cd0e5e5a4a676044fa830a0c34d3e5ce738fa97

## Change Summary

Implemented and independently checked generated module facade methods and lifecycle behavior on top of the Phase-16 bytecode path. Facades now carry runtime options, enforce ownership and compatibility before state access, expose typed exports/getters/function getters and permitted setters, preserve re-export/cell semantics, and release state on close.

## Files

- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/backend/jvm/JvmBytecodeEmitter.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/backend/jvm/Phase17SmokeTest.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/LyraArtifactKey.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/ModuleLifecycle.java`

## Behavioral Impact

Generated facades support the Phase-17 typed Java-facing members, immutable runtime options, lifecycle/owner checks, same-artifact closure authentication, mutable direct and re-exported bindings, dependency-instance isolation, and post-close state release. Existing scalar, aggregate, function, and module behavior remains covered by prior phases.

## Specification Impact

Specification Impact: none. This implements the existing generated-facade and lifecycle contract without adding artifact packaging, public compiler/loading APIs, CLI behavior, standard-library interop, or deferred language features.

## Risks

Artifact packaging, custom loading, public runtime/compiler APIs, CLI entry points, standard I/O, and full conformance remain later phases. Facade methods must continue to consume exact ABI plans and validated IR as metadata and loading are added.

## Follow-up Items

Phase 18 will add canonical metadata/source-map generation, deterministic class/JAR assembly, compatibility metadata, and atomic artifact publication.
