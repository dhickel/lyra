# CLI and Launcher Validation Lessons

## Topic

Phase 21 standalone CLI, runnable-JAR launcher, and script validation.

## Source References

- `.internal-dev/specifications/backend-runtime.md`, standalone entry point and CLI sections.
- `lyra-cli/src/main/java/io/mindspice/lyra/cli/LyraCli.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/LyraLauncher.java`
- `lyra-cli/src/main/scripts/lyra`
- `lyra-cli/src/main/scripts/lyra.bat`

## Key Takeaways

- The CLI `--` separator belongs only to `lyra run`; a direct `java -jar` launcher receives the JVM's argument vector literally. Stripping a leading `--` in `LyraLauncher` changes the program's observable arguments and is incorrect.
- Bundled artifacts can be loaded through the normal public runtime boundary while excluding embedded runtime classes from the child loader; this preserves one shared runtime domain without weakening the runnable-JAR inventory check.
- Facade metadata is packaging-mode-specific because the facade exposes the published canonical artifact metadata. Cross-mode byte comparisons should compare executable/helper classes and debug maps while treating the facade and packaging metadata as mode-specific.
- Relocation tests should invoke the Unix script from a different working directory, use paths containing spaces, and include a symlinked script path. Script tests should also verify all arguments are passed as one preserved vector.

## Project Relevance

These rules keep CLI parsing, in-memory execution, bundled launching, artifact loading, and process-script behavior aligned without adding a second runtime or changing the language argument contract.

## Open Questions

Windows execution requires a native Windows validation environment; the batch script is syntax- and structure-checked here, while `%*`, `%~dp0`, and configurable `JAVA_COMMAND` preserve the corresponding contract.
