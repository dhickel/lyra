# Public API Validation Lessons

## Topic

Phase 19 compiler/runtime API validation and generated-facade publication.

## Source References

- `.internal-dev/specifications/backend-runtime.md`, public Java API and artifact metadata sections.
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/artifact/ArtifactAssembly.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/LyraRuntime.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/api/Phase19PublicApiTest.java`

## Key Takeaways

- Public compilation options affect module revisions. Artifact assembly must preserve graph-owned revisions instead of recomputing revisions with empty options.
- Generated facades initially carry provisional metadata. Replacing a class-file `CONSTANT_Utf8` requires rebuilding the class-file tail because the final canonical JSON length can differ; the replacement must validate the class-file constant-pool structure.
- A content-prefix search for the provisional metadata can collide with a user string constant. A compiler-owned provisional marker makes the replacement target unambiguous in normal generated output.
- Function export metadata must expose the exact generated invocation member. Reserved facade names such as `close` therefore use `invoke$close` in the runtime export metadata and Java-name map, while getter names continue to use the base mangled export name.
- A loaded-artifact context must count in-progress instantiations while a factory runs. Otherwise a concurrent context close can clear the child loader before the new module instance is registered.
- Class-directory, thin-JAR, in-memory, and ordinary Java-consumer paths should all validate the same published metadata and exact bound method-handle behavior.

## Project Relevance

These checks cover the Phase 19 boundary without expanding into Phase 20 intrinsic I/O or later CLI behavior. They are reusable when adding new facade names, metadata options, or loader lifecycle operations.

## Open Questions

Bundled-JAR loading remains intentionally fail-closed until the later launcher phase supplies `LyraLauncher`.
