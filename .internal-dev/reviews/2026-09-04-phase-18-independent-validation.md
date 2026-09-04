# Phase 18 Independent Validation

## Scope

Validated the Phase 18 metadata, debug-map, and artifact-assembly implementation after the Phase-18 repair work, including the worktree Phase 18 sources and tests. The final code commit is recorded in the accompanying changelog. The review covered immutable publication objects, schema-1 canonical JSON/readers, source/hash and revision checks, BCI maps, class-directory output, thin JAR output, staged publication, and the fail-closed bundled-runtime boundary.

## Findings

- **F1 fixed:** nullable function exports used the unqualified function signature when assembling runtime metadata. An export declared `@nil Fn<;I32>` therefore failed its stable-ID/metadata identity check. Metadata projections now use the complete value contract while JVM invocation/authentication continues to use the unqualified function signature.
- **F2 fixed:** manifest continuation chunks allowed 71 value bytes after a leading continuation space, exceeding the JAR 72-byte line limit once CRLF was included. The continuation payload is now capped at 69 bytes.
- **F3 fixed:** metadata reading could map path and URI source IDs with the same textual value to the same module. Source decoding initially selected an unused matching module identity, preserving path/URI distinctions.
- **F4 fixed:** the source-order repair made source kind explicit in schema-1 metadata, used a single kind-plus-value comparator, included kind in artifact revisions, and rejected only true duplicate kind/value identities. This fixes exact path/URI round trips with coincident values.
- **F5 fixed:** manifest continuation chunks are capped so the complete UTF-8/CRLF line remains within the JAR 72-byte limit.
- No additional Phase 18 defects were found after the final repair.

## Risk Assessment

Phase 18 class-directory and thin-JAR assembly is operational and passes the external artifact checks. Bundled assembly remains intentionally fail-closed because the current runtime does not yet contain `LyraLauncher`; the existing test confirms rejection rather than producing an incomplete bundled artifact. Completing runnable bundled output remains later launcher/CLI work.

## Recommendations

Retain the nullable-contract, long-manifest, and path/URI-collision regressions. Re-run the external JAR/class-directory inspection when the launcher is introduced and enable the bundled success-path matrix then.

## Follow-ups

No specification change is required. The bundled launcher/runtime success path is a later-phase dependency, not silently implemented in this validation.
