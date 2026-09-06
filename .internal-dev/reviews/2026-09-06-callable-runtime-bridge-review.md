# Callable Runtime Bridge Review

## Scope

Read-only gpt-5.6-luna:high helper review of the runtime bridge against `/tmp/lyra-callable-baseline-7ryhmork/files`, followed by direct senior inspection and focused validation. Compiler callable persistence guards remain unchanged. The reviewer did not run builds.

## Findings

- P1, repaired: the initial Linkage implementation recomputed mutable sourceLocal state during validate while authenticating only artifactId. A caller-reused artifactId could reclassify existing imported authority. Final Linkage pins complete immutable ArtifactMetadata and final source-local classification. A same-ID/different-revision/module-inventory regression rejects substitution and preserves the original imported link.
- P2, addressed: compiled reverse-direction invocation was missing. The older producer now invokes a newer wrapper closure, which retains and calls the original producer.
- P2, addressed: some owner/epoch tests failed at the consumer before reaching the candidate. Added direct producer wrong-thread and fresh-consumer/retired-producer checks, independent of a physical JVM cast.
- P3, addressed: documentation now says explicitly linked authority rather than suggesting per-closure registration.

## Risk Assessment

No compiler-summary or failed-initializer escape support is claimed. Full named REPL callable persistence remains blocked. The discovered recursive captured-cell summary defect is pre-existing and separately tracked as GitHub issue #2. Ordinary AOT authentication and same-artifact initialization behavior are preserved.

## Recommendations

Keep callable resolver/storage guards. Complete certified flow, per-binding initialization and escaped-generation lifetime together before widening source-level admission.

## Follow-ups

Focused post-repair validation: 81 tests under `-Xverify:all`, zero failures/errors/skips. Full regression results belong to the linked changelog. Parent must own the next max senior pass; this child cannot grant another nested senior delegation layer.
