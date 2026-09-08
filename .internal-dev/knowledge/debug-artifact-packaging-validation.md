# Debug Artifact Packaging Validation

## Topic

Phase-11 deterministic debug artifact packaging: versioned capability metadata, embedded source/resolution reconstruction, fixed production closure collection, and the REPL launcher composition.

## Source References

- `.internal-dev/specifications/repl.md` (artifacts and compatibility), `backend-runtime.md` (artifact metadata).
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/artifact/{ArtifactAssembly,BundledRuntime,ArtifactOutputWriter}.java`, `io/mindspice/lyra/compiler/api/DebugArtifactContext.java`.
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/{ArtifactMetadata,ArtifactMetadataReader,ArtifactRevision,ReplCapability,LyraRuntime}.java`.
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/ReplLauncher.java` and the new artifact tests across all four modules.

## Key Takeaways

- The debug capability must be orthogonal to the execution profile: attaching it to ATTACHABLE would change ordinary generated code, while a boolean-only marker would lose the versioned closure declaration. `replCapability:{schema:1}` plus the exact fixed dependency list keeps normal bytes untouched.
- Conditional revision inputs are the only way to extend a hash contract without breaking legacy fixtures: the capability tag enters `ArtifactRevision.compute` only when declared, and the frozen pre-Phase-11 normal fixture proves the ordinary digest is unchanged.
- The canonical writer must emit debug-normal metadata WITHOUT `executionProfile` (the NORMAL default) but WITH the extension block; the reader must enforce exactly that asymmetry, otherwise a debug encoding either gains a foreign field or loses its context.
- Collection from one fixed anchor class per package prefix is what makes CLI/JLine/test exclusion structural: the shaded CLI jar hosts every module in one code source, so prefix filtering per anchor is the exclusion mechanism, not classpath filtering.
- The missing-closure boundary is a `Class.forName` of the fixed REPL anchor inside `BundledRuntime`; compiler-only classpaths therefore produce the actionable packaging error, and full debug bundling only succeeds where lyra-repl is present (the CLI distribution).
- Reconstruction must reuse `debugCapable(true)` on the rebuilt request: a plain NORMAL rebuilt publication filters `imports`/options out of its metadata, making topology comparison silently vacuously equal.
- The ReplLauncher compile probe must catch `LinkageError` (NoClassDefFoundError) around the compiler reference and map it to an actionable classpath message; without that guard a missing external compiler jar dies at JVM linkage instead of reporting the documented layout.
- Top-level module effect syntax that works is the selected-import direct call form (`import std->io->{println} (println "X")`); qualified `::` bracket calls failed in this context during test development.

## Project Relevance

These checks protect the normal AOT boundary while making debug artifacts provably self-contained, reconstructible, and deterministic. Future packaging or launcher work should preserve the fixed-anchor collection and the conditional revision input patterns.

## Open Questions

- Phase 12 activation will replace the launcher's compile-probe preflight with listener bootstrap/wait; the probe cost disappears there.
- A future relocation/shading of the production modules would need new fixed anchors; the current design rejects relocated code sources by prefix.
