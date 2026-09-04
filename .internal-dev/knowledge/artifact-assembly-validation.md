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

## Project Relevance

These checks protect deterministic publication and prevent metadata identity drift from appearing only in nullable, long-value, or mixed path/URI inputs. Bundled packaging must remain fail-closed until the production launcher is present.

## Open Questions

When bundled launcher work lands, add a successful bundled-JAR subprocess matrix in addition to the current missing-launcher rejection test.
