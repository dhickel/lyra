# Editor Integration

## Topic

JavaFX tooling around Lyra's existing compiler, session API and JDI source maps.

## Source References

- `specifications/editor.md`, `specifications/repl.md`, `specifications/backend-runtime.md`.
- `lyra-editor/src/test/java/io/mindspice/lyra/editor/EditorRuntimeTest.java` and `EditorWindowTest.java`.
- `lyra-repl/.../SourceRegistry.java`, `remote/RemoteClient.java`, `remote/RemoteSessionExecutionTest.java`.
- OpenJFX: https://openjfx.io/openjfx-docs/; RichTextFX: https://github.com/FXMisc/RichTextFX.
- JDK jpackage: https://docs.oracle.com/en/java/javase/25/docs/specs/man/jpackage.html.

## Key Takeaways

- A REPL console command starts with a single backslash; every colon-leading unit, including `:Type[]` and `::function[]`, is ordinary source. Test the distinction through the visible GUI, not only a backend helper.
- Sessions own compiler identities `repl/submission-<evaluation UUID>.lyra`, even when diagnostics use a file origin. Register the actual protocol request ID before binding JDI locations. Normal imports use their own source IDs.
- Protocol v2 admits one controller. Initialize a direct session host before constructing its server when a test expects a nonzero initial revision; the real application activation surface has its own registration gate.
- Grammar/typing errors in editor fixtures are not reasons to invent editor-specific language semantics. Namespace value reads use `module->:.name`. Local lambdas may need complete return annotations, and a following direct-access expression can be parsed as access on the preceding initializer; use unambiguous block/source forms already supported by the compiler.
- An invalid token stream is intentionally not exposed by the lexer. Re-lexing the prefix before the diagnostic can preserve correct earlier highlighting without synthesizing tokens.
- UI tests must wait for the evaluation control to become enabled, not just for its output to appear: result output can precede committed-binding refresh.
- Editor history follows the console's source-unit contract rather than command text: a load may add exact returned source but never its command/path; protocol-v2 loads intentionally return no source. Clear history only after an `OK` reset, and preserve it for failed or busy reset attempts. Exercise successful remote load and reset with visible Up-history navigation.
- Use complete lines for breakpoint rebasing. A character-only prefix/suffix diff can match the first character of a deleted line against the surviving next line and drop the wrong breakpoint.
- jpackage's default jlink options remove native commands, including the `java` launcher required by editor workers. Override those defaults while retaining `bin/java`; include the JDI/JDWP modules. Test an actual worker under the embedded runtime.
- A JEP 493 runtime-linkable JDK without a `jmods` directory cannot link a new image containing `jdk.jlink`. Use the editor's explicit module set, not `ALL-MODULE-PATH`; the editor does not need to redistribute packaging tools. The OpenJDK contract is recorded at https://bugs.openjdk.org/browse/JDK-8317420.
- Runtime-image linking also rejects distribution-modified configuration such as `conf/security/java.security`. Do not alter the installed JDK or bypass its integrity checks. A pristine packaging JDK or the explicit `LYRA_EDITOR_RUNTIME_IMAGE` input uses the ordinary jpackage contracts; validate the resulting embedded runtime and child-worker startup.
- jpackage can preserve absolute links from a supplied runtime image, including distribution-managed `conf`, `lib/security`, and `tzdb.dat`. Stage the explicit runtime with links dereferenced before packaging, then check the result for links outside its bundle. Running on the build host alone does not detect that portability failure.
- A conditional graphical test is additional editor release evidence; the existing Phase 24 exact-method inventory still certifies backend/REPL requirements. Keep its old gates intact and extend target preservation/layout checks for the new editor module.
- A block's opening brace can have a generated prologue location. Derive the requested debug entry from its first AST form, and restrict entry locations to callable `invoke` methods; user breakpoints still bind to all executable locations.
- Function classification must check the outer callable type. `Array<Fn<;I32>>` contains functions but is a data binding, not a directly runnable function.
- `Files.move(..., ATOMIC_MOVE)` can replace an existing destination even without `REPLACE_EXISTING`. File renames use the ordinary no-replacement move contract; atomic replacement remains appropriate for explicitly authorized source saves.
- Saves run asynchronously. Before a close/rename/run continuation, check whether more edits arrived and save those too. Publish child runtimes under the same lifecycle lock used by Stop/dispose, and close resources whose asynchronous completion is no longer owned by a live window.
- The source editor's Enter handler must search for a preceding newline from `caret - 1`, including `-1` at document start. Searching from zero mistakes an initial newline for a preceding line break and requests an inverted text range. The graphical edit/run test exercises this edge.
- Declare compiler/runtime and the directly referenced RichTextFX companion APIs explicitly. OpenJFX's portable facade dependencies resolve OS-classified bytecode JARs; Maven's bytecode analyzer treats those as different artifacts. The editor POM limits the documented analyzer exception to its three pinned, explicitly declared OpenJFX APIs. Other dependency warnings retain the existing release-gate treatment.
- Finish file creation before starting Phase 24. It compares the complete non-ignored Git status before and after its long benchmark run; adding even a distribution README during that run correctly fails the workspace-stability gate. Do not weaken that check or reuse benchmark results in place of the mandatory fresh gate.

## Project Relevance

These boundaries let desktop tooling reuse exact initialized storage and compiler diagnostics without changing normal artifact semantics or moving live values across threads. They also prevent UI mocks or an untested package from being mistaken for a working development environment.

## Open Questions

Target-platform installer/signing validation and richer compiler local-variable debug metadata remain separate follow-up work.
