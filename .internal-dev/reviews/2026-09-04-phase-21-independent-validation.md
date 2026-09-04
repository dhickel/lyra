# Phase 21 Independent Validation Review

## Scope

Validated the standalone CLI, in-memory runner, compiler output integration, class-directory/thin/bundled artifact workflows, runnable-JAR launcher, exit handling, argument forwarding, output publication behavior, scripts, and runtime dependency boundary against Phase 21 of `backend-runtime.md`.

## Findings

- The CLI now has strict fixed command parsing, stable help/version/usage output, path and logical roots, repeatable source roots, exact option validation, in-memory `run`, output format selection, default output naming, force/refusal handling, diagnostic rendering, and exact bundled-root `main` validation.
- Bundled artifacts are accepted by the normal runtime loading boundary while embedded runtime classes remain parent-shared, and bundled packaging requires the real `LyraLauncher` inventory.
- The launcher invokes the exact typed main export, preserves direct JAR arguments literally including a leading `--`, wires process UTF-8 streams, closes module/loading resources in `finally`, preserves invocation failures as primary, and reports close failures as suppressed.
- Unix and batch scripts resolve their installation-relative CLI JARs, preserve argument vectors, and support configurable JVM command/options. The Unix script also resolves a symlinked entry path.
- No Phase 21 defect remains after focused and broad validation.

## Risk Assessment

Low for the validated Unix/Java 25 path. The Windows batch script was structurally checked but not executed because this environment has no native Windows runner. Maven emits only existing Java 25 deprecation warnings for `ThreadDeath` handling and shade overlap warnings; no test or build failure was observed.

## Recommendations

Retain the direct-JAR argument distinction: only `lyra run` consumes `--` as its command separator. Continue comparing cross-packaging executable/helper class bytes and debug maps separately from mode-specific facade metadata.

## Follow-ups

Phase 22 owns the broader language/backend conformance and release sealing matrix. Windows-native script execution and preview-required runnable-artifact checks remain appropriate in that broader integration campaign.
