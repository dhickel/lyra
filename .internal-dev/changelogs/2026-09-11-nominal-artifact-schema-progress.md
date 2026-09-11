# Nominal artifact schema progress

## Date

2026-09-11

## Git Commit

`67dcfb16a4d0cf4c67daecf65864e49f63203aeb` (baseline).

## Change Summary

Added artifact schema 2 with a required nonempty exact nominal schema section,
two-pass recursive contract decoding and schema-aware export parsing. The complete
schema section participates in a domain-separated artifact revision extension.
Schema-1 publications remain unchanged and reject nominal contracts.

## Files

Runtime artifact metadata/reader/revision, scoped canonical parser, schema encoding,
facade metadata-version gate, metadata tests, fuzz selection and development records.

## Behavioral Impact

Runtime metadata can represent recursive nominal declarations and exports without
guessing types or replaying source. Wrong versions, identities, schema order, missing
references and changed field contracts reject. Canonical round-trips retain fields
and constructor order. Existing legacy and debug schema-1 metadata remains readable.

## Specification Impact

The accepted versioned nominal metadata requirement now has a concrete schema-2
field order, two-pass resolution contract and revision-hash extension documented in
backend-runtime.md. No source semantics or live-object authority rule changes.

## Validation

Focused nominal and legacy/debug metadata tests, extended `tools/fuzz-language.sh
-q`, and final ordinary `mvn -q test` passed. Extended validation includes two
publication seeds with 1,800 cases each plus existing runtime/compiler campaigns.
The final ordinary run includes the final valid-kind and unknown-member-reference
corruption assertions. `git diff --check` passed. No release audit is claimed.

## Risks

This is WIP full-feature integration. Metadata integrity is not live-object producer
authority. The compiler does not yet publish emitted nominal classes through this
schema; object class plans/emission, exact loading/authentication, equality and
retained sessions remain mandatory. Semantic control-sensitive heap work also
remains open. No executable nominal or release audit claim is made.

## Follow-up Items

Commit validated metadata work, then continue compiler artifact/schema transport,
generated object classes, runtime authority and remaining semantic/session tests.
Do not treat metadata round-trips as the requested feature finalization.
