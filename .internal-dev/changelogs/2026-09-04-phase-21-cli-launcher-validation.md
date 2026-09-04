# Phase 21 CLI and Launcher Validation

## Date

2026-09-04

## Git Commit

3c5ae7ce036193c2f6825f055d18ef535b5e33e4

## Change Summary

Completed independent Phase 21 repair and validation for the standalone CLI, runtime launcher, bundled artifact loading, compiler packaging integration, and Unix/Windows distribution scripts. Added assertion-grade CLI and subprocess coverage for command parsing, source-root resolution, in-memory execution, output formats, artifact inventory, verification, diagnostics, deterministic replacement, and argument forwarding.

## Files

- `lyra-cli/src/main/java/io/mindspice/lyra/cli/LyraCli.java`
- `lyra-cli/src/test/java/io/mindspice/lyra/cli/Phase21CliTest.java`
- `lyra-cli/src/main/scripts/lyra`
- `lyra-cli/src/main/scripts/lyra.bat`
- `lyra-cli/pom.xml`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/LyraLauncher.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/LyraRuntime.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/ArtifactMetadata.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/artifact/ArtifactAssembly.java`

## Behavioral Impact

CLI usage and option failures are strict and stable, path/logical roots follow the source-root contract, `run` forwards only tokens after its separator, bundled compilation requires the exact root `main :Fn<Array<String>;I32>` export, bundled JARs include and reuse the real launcher/runtime without defining duplicate runtime classes, and direct runnable-JAR arguments including a literal leading `--` are preserved. Unix scripts resolve relocation and symlink paths; the batch script preserves `%*` and supports a configurable Java command.

## Specification Impact

Specification Impact: none. The implementation follows the existing Phase 21 command, launcher, artifact, lifecycle, argument, exit-status, and script contracts in `backend-runtime.md`.

## Risks

The environment is Unix-based, so the Windows batch script received structural and syntax-oriented validation rather than native execution. Existing worktree deletions and untracked phase outputs were preserved.

## Follow-up Items

Phase 22 remains responsible for the broader conformance and integration sealing matrix; no deferred language or host-integration behavior was added here.
