# Artifact Assembly Validation Lessons

## Topic

Phase 18 metadata, debug-map, and deterministic artifact validation.

## Source References

- `.internal-dev/specifications/backend-runtime.md`, especially artifact metadata, debug-map, and deterministic packaging requirements.
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/artifact/ArtifactAssembly.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/artifact/ArtifactOutputWriter.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/ArtifactMetadataReader.java`
- `.internal-dev/reviews/2026-09-04-phase-18-independent-validation.md`

## Key Takeaways

- A nullable function export has two related but distinct spellings: the complete exported value contract includes top-level `@nil`, while the JVM callable/authentication signature remains the unqualified `Fn<...>` signature. Artifact identity metadata must use the former.
- JAR manifest continuation lines include both their leading space and CRLF in the 72-byte limit. A continuation payload therefore has at most 69 bytes.
- Serialized source IDs must carry an explicit `sourceKind` tag in addition to their stable value spelling. Reader-side decoding must use that tag and a single kind-plus-value comparator so equal path/URI values remain distinct while true duplicate identities are rejected.
- External artifact checks should compare generated class bytes across class-directory and thin-JAR modes, but should expect packaging metadata and artifact revisions to differ. Debug-map bytes remain equal for identical generated classes.
- Attachable publications must embed reachable source snapshots even when the ordinary `includeSources` option is false; root attachment metadata is a reconstruction/debug boundary, not an opt-in source omission.
- Root registration must bind only the live generated root facade, while structural tuple/function classes are retained in a shared loaded-artifact base and each root gets an independent lifetime extension. Reopening the registration must not retire the root's structural types.

## Project Relevance

These checks protect deterministic publication and prevent metadata identity drift from appearing only in nullable, long-value, or mixed path/URI inputs. Bundled packaging must remain fail-closed until the production launcher is present.

## Open Questions

When bundled launcher work lands, add a successful bundled-JAR subprocess matrix in addition to the current missing-launcher rejection test.

## Phase-11 Independent Validation Corrections

- JVM facade metadata must be split into modified-UTF-8-sized constants for debug-capable artifacts; a single constant fails once canonical debug options exceed the class-file constant limit. Normal artifacts retain the legacy single-constant publication path for byte compatibility.
- Embedded source entry names need a deterministic collision escape because hash-based URI names can otherwise equal a valid path source name. Preserve historical names unless a path/URI collision is present.
- Debug-generated packages must not occupy `io.mindspice.lyra.compiler` or `io.mindspice.lyra.repl`, since those namespaces are part of the bundled production closure even when an in-memory artifact happens to load them first.
