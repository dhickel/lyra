# Phase 12 Independent Typed IR Validation Review

## Scope

Independent adversarial review and in-scope repair of the Phase-12 closed typed IR after the turn-limited implementation attempt. The review covered the IR node hierarchy, lowering, publication boundary, metadata, flow/provenance projection, failure sites, evaluation order, initialization plans, captures/cells, module state, imports/exports, recursive linkage, and Phase-11 semantic regression suites. Phase-11's owner-approved same-package resolver-certificate exception was preserved. JVM backend/emission, runtime, CLI, and public Java API work were not started.

## Findings

- Nil coalescing had been lowered through a fabricated `NIL_NARROWING` `LYR-CONVERT` runtime check without a semantic failure site. It now uses an explicit non-failing `Narrowing` child, and the unused check kind was removed.
- Deterministic `String[value]` conversion had been classified as a runtime conversion failure. Failure-site generation and lowering now limit invocation-fatal conversion checks to numeric explicit conversions.
- Callable summary identities were not recovered for calls inside lambda bodies because lowering searched compact flow events rather than solved summary call records. Lowering now matches producer call sites against canonical summary references, and validation checks that match.
- Producer source-site paths and aggregate origin sites could be silently absent or filtered. Eager dependency/effect paths, event sites, nil provenance, callable creation sites, and aggregate origins are now checked; missing aggregate provenance fails lowering rather than being dropped.
- Initialization order accepted duplicate entries, and several metadata collections did not enforce canonical uniqueness/order. The relevant IR models now reject duplicate/noncanonical schedule, export, SCC, capture, closure, and evaluation data.
- The validated publication path now revalidates candidates, `TypedIr.requireValidated()` uses the consumer gate, and typed-IR equality/hash distinguishes candidate from validated publication.
- Constant validation now models F32 arithmetic at single precision, detects non-finite intermediate results, checks F32 explicit integer conversions with the correct target precision, and avoids malformed-arity indexing failures.
- Tests now cover all closed IR variants from supported source, exact visitor order, module import/export and initialization order, captures/cells/recursive linkage, deep immutability, callable summary identity, non-failing coalescing/text conversion, duplicate schedules, and non-finite/out-of-range negative fixtures.

## Risk Assessment

The current typed language boundary deliberately rejects receiver member direct-call forms with the existing structured `LYC-TYPE-013` diagnostic; the IR validator documents this boundary and will not erase receiver semantics. This remains distinct from the accepted Phase-11 topology certificate exception. No Phase-13+ code is present in the reviewed IR path.

The IR remains internal and nonserialized. Constructor-created candidates are intentionally retained for negative fixtures, but validated traversal/publication entry points require the sealing validator. Numerical thresholds, JVM emission, runtime behavior, CLI behavior, and public Java API behavior remain unvalidated because they are later phases.

## Recommendations

- Keep the later emitter on the validated `TypedIr` consumer gate and consume frozen flow/initialization metadata rather than reconstructing semantic flow.
- Preserve the current distinction between source expressions and synthetic non-source edges/checks when adding backend nodes.
- Revisit member direct-call lowering only under an explicit language-contract decision or a separately completed typed member-call slice.

## Follow-ups

None within Phase 12. Phase 13+ remains out of scope for this review.
