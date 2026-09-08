# Phase 11 — Deterministic Debug Artifact Packaging

## Date

2026-09-08

## Git Commit

f9a9573 (planning/implementation baseline; changes are uncommitted worktree edits)

## Change Summary

Implemented the accepted Phase-11 debug artifact packaging contract on top of the Phase-05 attachable metadata foundation:

1. Versioned debug capability (`ReplCapability`, schema 1) across `ArtifactMetadata`, `ArtifactMetadataReader`, `ArtifactRevision`, `ReplCapability`, `LyraRuntimeConstants`, and the runtime loader. Debug publications always embed every reachable source snapshot, the canonical import resolution topology with exact spans, and the reproducible scalar options, and declare the exact fixed `lyra-compiler`/`lyra-repl`/`lyra-runtime` closure. Ordinary schema-1 encodings, revisions, and runtime-only bundles are byte-identical (frozen pre-Phase-11 fixture regression).
2. Packaged reconstruction: new `DebugArtifactContext` rebuilds the recorded module graph/revisions through the ordinary compiler pipeline from embedded sources only; no resolver objects, no edited/deleted original files, no initializer execution, no IR deserialization. Rebuilt mismatches are structured compatibility errors.
3. Bundled closure collection: `BundledRuntime` now collects the fixed runtime/compiler/REPL production code sources by package prefix (REPL anchor discovered without a static compiler->REPL dependency); missing or conflicting inventories are actionable `ArtifactAssemblyException`s. CLI/JLine/JMH/JUnit/test/credential material is excluded by construction.
4. Fixed `ReplLauncher` (lyra-repl) with profile-aware preflight: self-locating debug bundled `java -jar` composition, explicit artifact-location classes/thin composition, fixed compile probe that executes no Lyra source, exact `main`/exit semantics. `LyraLauncher` remains dependency-free; the runtime compares launcher spellings only.
5. Compiler plumbing: `CompileRequest.debugCapable`, `ArtifactAssemblyOptions.replCapable`, assembly/imports/options/dependency wiring, and profile-aware manifest Main-Class selection; SESSION+debug rejected at request construction.

Tests added: `DebugArtifactMetadataTest` (runtime), `ReplPackagingCompatibilityTest`, `BundledRuntimeCollectionTest`, `LegacySchema1EncodingTest` (compiler), `ReplArtifactIT` (CLI, full subprocess matrix with `-Xverify:all`).

## Files

- lyra-runtime: ReplCapability.java (new), ArtifactMetadata.java, ArtifactMetadataReader.java, ArtifactRevision.java, LyraRuntime.java, LyraRuntimeConstants.java, src/test/resources/legacy/artifact-v1-normal.json (new), DebugArtifactMetadataTest.java (new)
- lyra-compiler: CompileRequest.java, ArtifactAssembly.java, ArtifactAssemblyOptions.java, ArtifactOutputWriter.java, BundledRuntime.java, ArtifactAssemblyException.java, DebugArtifactContext.java (new), LyraCompiler.java, ReplPackagingCompatibilityTest.java (new), BundledRuntimeCollectionTest.java (new), LegacySchema1EncodingTest.java (new), src/test/resources/legacy-artifact-v1-normal.json (new)
- lyra-repl: ReplLauncher.java (new)
- lyra-cli: ReplArtifactIT.java (new)
- .internal-dev/specifications: repl.md, backend-runtime.md, decisions.md

## Behavioral Impact

- Normal artifacts: none (byte-identical metadata, revisions, runtime-only bundles, unchanged launcher).
- Debug-capable artifacts: embedded source context, declared closure, debug bundled Main-Class is `io.mindspice.lyra.repl.ReplLauncher`; classes/thin debug layouts launch through the explicit-location composition with the external closure.
- SESSION profile compilations reject the debug capability at request construction.
- Compiler-only classpaths produce an actionable missing-REPL-closure error for debug bundled packaging; full debug bundling executes in the CLI distribution.

## Specification Impact

`repl.md` artifacts/compatibility and status sections, `backend-runtime.md` artifact metadata section, and `decisions.md` (dated Phase-11 decision) now describe the versioned debug capability, the reconstruction boundary, and the fixed launcher composition.

## Risks

- The launcher preflight compile adds startup cost to every debug bundled run; acceptable until Phase 12 activation replaces the composition.
- The REPL closure anchor is a fixed class-name string shared between `BundledRuntime` and `ReplLauncher`; a rename must update both (asserted by the CLI integration test).

## Follow-up Items

- Phase 12: run/compile `--repl`, listener bootstrap, wait, runtime properties, and activation of the ReplLauncher composition.
- Phase 14: fold `ReplArtifactIT`/`ReplPackagingCompatibilityTest` evidence into the Phase-24 REPL coverage matrix and run the full release audit.
