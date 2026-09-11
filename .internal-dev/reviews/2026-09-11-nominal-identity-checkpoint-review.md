# Nominal identity checkpoint self-review

## Scope

Standalone compiler NominalTypeId, identity tests, accepted struct/class contracts
and active full-feature plan. This is a self-review, not independent qualification.

## Findings

- Identity includes module kind/key, normalized source revision, exact name and
  same-name occurrence. It does not accidentally use a graph-local declaration
  ordinal, field shape, absolute source span or alias as persistent identity.
- Equality and natural ordering use the same normalized identity components.
  Canonical field lengths count UTF-8 bytes; Unicode/URI fixtures exercise this.
- Invalid names/revisions/negative occurrences fail at construction. Resolver
  issuance and live schema/object authentication remain separate responsibilities.
- Fixed-vector and four-seed tests use independent input tuple models rather than
  production identity helpers to calculate equality expectations.
- No source grammar is enabled, no sealed type/IR invariants are relaxed, and no
  existing valid-source test is changed to accept unsupported emission.
- Earlier discussion ambiguities are explicit: mutable method slots are allowed;
  saved references keep implementation and receiver; contextual self never grants
  external replacement lambdas private privileges.

## Risk Assessment

Low integration risk for this standalone identity utility; high remaining scope
for the overall feature. It has not yet been integrated into any compiler phase or
runtime loading path. Specifications intentionally lead executable support.

## Recommendations

Keep this as a WIP checkpoint. Implement grammar and nominal type/schema collection
next, preserving descriptor replay, exhaustive visitors and structured diagnostics.
Do not claim full support until source execution, Java artifacts and sessions pass.

## Follow-ups

All phases in plans/nominal-types/plan.md remain open. Final validation results are
recorded in the associated changelog; no release or independent audit is claimed.
