# JavaFX Development Editor

## Date

2026-09-08

## Git Commit

4a865f666521923217a68b1f9a9ffc99656428a4

This identifies the dirty-worktree baseline; no commit was created for this change.

## Change Summary

Added the optional JavaFX editor module with directory browsing, source tabs, compiler highlighting/checking, project definitions, persistent REPL development, configurable function entries and actual JDI debugging. Added safe file handling, development helpers, launchable distributions, documentation and source-to-execution tests.

## Files

- `lyra-editor/`: fourteen production Java classes, stylesheet, Maven configuration, shell/Windows launchers, ZIP assembly and five test classes.
- `tools/lyra-editor.sh`, `tools/package-editor.sh`: repository launcher and native application-image packager.
- `examples/editor/`, `docs/editor.md`: working import/state/debugging example and user guide.
- `pom.xml`, `README.md`, `AGENTS.md`: optional module integration and usage/testing guidance.
- `tools/phase24-release-audit.sh`: five-module layout, editor-only JavaFX boundary, production scope scan and editor target preservation/comparison. Existing backend requirement and benchmark gates remain intact.
- `.internal-dev/specifications/editor.md`, `index.md`, `decisions.md`, `knowledge/editor-integration.md`, and the editor readiness review: ownership contracts, decisions and reusable validation lessons.

## Behavioral Impact

Opening/checking a project never executes code. Unsaved buffers participate in import/type checks. Run saves source, starts an owned JVM, initializes the selected file once and invokes its top-level function. Subsequent REPL evaluations retain real initialized state; editor targets do not change the standalone executable main ABI. Selection/form evaluation, explicit load/reload/reset, cancellation, separate stdin and external loopback REPL attachment use existing session/protocol semantics.

JDI supplies executable source breakpoints, function-entry stops and stepping across files, with passive argument/field inspection. Running debug source remains read-only until Stop. Source saves retain UTF-8/BOM/newline conventions, reject external conflicts and replace atomically. Renames refuse existing destinations; asynchronous save/close and runtime publication preserve edits and process ownership.

## Specification Impact

New `specifications/editor.md` owns desktop presentation, buffers, project settings/recovery, child runtime and debugger lifecycle, packaging and editor validation. The language, backend/runtime and REPL specifications retain their existing semantics and public contracts. The specification index and durable decisions are updated.

## Validation

- `mvn -q test -Dlyra.editor.uiTests=true`: 986 tests, zero failures/errors/skips, including 22 editor tests and both real-display GUI workflows.
- `mvn -q -pl lyra-editor verify -Dlyra.editor.uiTests=true`: all 22 editor tests plus the packaged-classpath integration test passed.
- Source integration proves one-time initialization, persistent mutation, non-executing type checks, compiler/runtime failure identity, independent stdin, cancellation, external attachment/detachment, aliases, imported-module breakpoints and line stepping. The shipped example is compiled and executed.
- GUI tests prove open/highlight/edit/save/run/REPL behavior, Enter at an empty first line, selecting and persisting an entry, stepping and stopping a paused process. The rendered layout was inspected; screenshot: `lyra-editor/target/editor-smoke.png`.
- Native packaging with `LYRA_EDITOR_RUNTIME_IMAGE=/usr/lib/jvm/java-25-openjdk` passed. The embedded Java 25 runtime ran `EditorDistributionIT.Probe --ui` and reported `PACKAGED_REPL=42` and `PACKAGED_GUI=42`; native `--help` passed. No native-bundle links resolve outside the application. ZIP launchers, guides and example inventory were verified.
- Dependency analysis passed with only the repository's existing JUnit analyzer warnings. Direct API dependencies are declared; only the three pinned OpenJFX portable/classified artifact pairs have a documented module-local analyzer exception.
- Initial Phase 24 attempt passed its clean build, backend conformance, packaging and fresh Phase 23 performance gate, but correctly blocked on dependency declarations and a distribution README added during its workspace-stability check. Those issues were corrected before the final run.
- Final Phase 24 release audit: **PASS** against the completed, stable worktree. Of 167 matrix rows, 156 passed, 10 retain their existing deferred classifications, and native Windows validation is N/A on this Linux host; zero rows are blocked. The fresh Phase 23 performance gate, exact REPL coverage gate, dependency classification, and target/workspace preservation all passed. Evidence: `target/phase24-audit/audit-summary.txt` and `audit-summary.json`.
- Shell syntax and `git diff --check` passed. Existing unrelated dirty/deleted/untracked backend files were preserved.

## Risks

Arbitrary function-body local values are unavailable because the compiler does not emit JVM local-variable tables; source lines, arguments and captured fields are available. Nested functions need their lexical environment. Tail-call elimination and generated bridges affect debugger stops/frames. External REPL attachment does not attach JDI or forward host stdio. Linux desktop/native validation does not qualify Windows or macOS installers.

The installed distribution JDK cannot be trimmed with jlink because its configuration differs from its runtime-linking hashes. The explicit runtime-image packaging route is validated; it stages linked configuration/security/time-zone files into the application. A pristine packaging JDK remains suitable for the default jlink route.

## Follow-up Items

Validate desktop launchers/installers on each additional target OS/architecture before distributing them. Richer body-local inspection requires a separately specified compiler debug-metadata change.
