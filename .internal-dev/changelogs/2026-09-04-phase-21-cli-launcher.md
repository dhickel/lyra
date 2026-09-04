# Phase 21 Standalone CLI and Launcher

## Date

2026-09-04

## Git Commit

2f274c0bcf3ce65b63d2bcca437adddf644f8a0f

## Change Summary

Implemented the fixed standalone Lyra CLI and bundled launcher. The CLI supports exact `run`, `compile`, `--help`, and `--version` commands, root/source-root resolution, argument forwarding, deterministic output formats, atomic/refused writes, and stable exit behavior. Bundled artifacts now include the real launcher/runtime boundary and can execute exact `main` exports.

## Files

- `lyra-cli/pom.xml`
- `lyra-cli/src/main/java/io/mindspice/lyra/cli/LyraCli.java`
- `lyra-cli/src/main/scripts/lyra`
- `lyra-cli/src/main/scripts/lyra.bat`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/LyraLauncher.java`
- CLI/runtime packaging integration and subprocess tests

## Behavioral Impact

`run` compiles and executes in memory, forwards only arguments after `--`, validates `main :Fn<Array<String>;I32>`, preserves invocation/close failure precedence, and never closes process streams. `compile` supports classes, thin JAR, and bundled JAR output with exact defaults, refusal/force behavior, and atomic publication. The launcher reuses the runtime loader and exact typed main boundary for runnable bundled JARs. Unix and Windows scripts preserve argument vectors and support relocation.

## Specification Impact

Specification Impact: none. The implementation follows the existing Phase-21 CLI, launcher, main, argument, format, exit, lifecycle, and script contract.

## Risks

Windows script execution received structural validation only in the Unix environment. Preview-required runnable JARs still depend on the caller supplying `--enable-preview` as required by artifact metadata.

## Follow-up Items

Phase 22 will exercise the complete conformance and integration matrix, including native Windows coverage where available, preview subprocesses, all language constructs, and cross-format execution.
