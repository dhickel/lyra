# REPL Validation Boundaries

## Topic

Validation rules and false assumptions for the optional persistent REPL surfaces.

## Source References

- `.internal-dev/specifications/repl.md`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/LyraSession.java`
- `tools/phase24-release-audit.sh`
- `tools/phase24-requirement-matrix.tsv`

## Key Takeaways

- Local `:type` must compile against the immutable committed session snapshot without reserving source history, publishing metadata, advancing the revision, or executing bytecode. For a top-level sequence, the query result is the final form's type; an empty/declaration-only unit is `Unit`.
- The current session compiler maps compiler-owned submission spans back through the passive source origin. Local console diagnostics should render those mapped spans rather than exposing the internal candidate `SourceId`.
- Authenticated remote type queries use the owner-dispatched server-side query path and the same immutable session snapshot as local `:type`; adapters that do not expose a live query remain structured `UNAVAILABLE` results rather than fabricated values.
- Phase 24's older audit assumed three modules and only three test-report roots. Adding `lyra-repl` requires updating target preservation, layout, report inventory, dependency/jdeps classpaths, and explicit matrix rows. A declared blocked REPL row is necessary to keep a legacy audit from reporting a false overall PASS while the typed linker is absent.
- JLine provider artifacts are loaded through service/provider selection, so Maven dependency analysis may report `jline-terminal-ffm` as unused even though it is required at runtime. Classpath audit tooling must classify that intentional provider dependency and give `jdeps` the declared JLine closure for thin CLI inspection.

## Project Relevance

Passing console/protocol tests alone does not prove persistent bytecode linkage, result extraction, import/reload ownership, or application attachment. Scalar, source-local aggregate and compiler-certified callable linkage now have executed compiler/runtime/API tests and immutable snapshots; import/reload and application attachment remain separate unfinished gates. See `repl-callable-linkage.md` and `repl-aggregate-linkage.md` for the current boundaries.

## Open Questions

- How should imported module revisions and ownership evidence cross the certified source-local session domain without invalidating old references or weakening attachment visibility?
- How should configured/application roots and escaped callable-generation ownership integrate with the existing owner-thread control boundary?
