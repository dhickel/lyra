# Nominal factory transfer self-review

## Scope

Reviewed constructor summary targets, source sealing, transitive solver deferral,
caller-owned heap substitution, write ordering and test oracles. This is a local
self-review, not independent review or a nominal-feature release audit.

## Findings

- Constructor targets carry the exact declaration, source site/reference and
  signature. Non-constructor calls cannot consume these targets as function values.
- Static summary materialization cannot construct objects without a caller heap.
  Constructor dependence is detected through a cycle-guarded callee traversal.
- Constructor and surrounding writes use shared event-major sequence keys.
  Per-activation call memoization also covers write-target argument projections,
  avoiding duplicate initialization when one result participates in multiple terms.
- Mutable module function declarations are live linked slots, not always lexical
  captures. Ordinary graphs now seed and transfer their storage writes as sessions
  already did. Post-construction reads discard pre-construction slot assumptions.
- Tests compare selected source lambda identities against an independent ordered
  slot model. They do not use a production transfer helper to compute expectations.
- Remaining semantics are substantial: joined branch constructor effects need
  path-sensitive treatment; loops, recursive constructors, receiver activation
  restoration and imported/captured-only heap mutation need additional work.
  Current nominal heap execution cannot be labeled the full language backend.

## Risk Assessment

The added transfer is intentionally a WIP boundary. It proves focused straight-line
and transitive factory cases, not complete object semantics or runtime execution.
Nominal schemas, exact JVM fields, object lifecycle authority and retained sessions
remain mandatory. No existing producer check or unsupported-emission assertion was
removed to turn these semantic tests into claimed executable coverage.

## Recommendations

Keep the full goal open. Extend control-sensitive heap transfer and source-linked
validation alongside the independent runtime schema and JVM implementation. Preserve
existing exact type, source proof and owner checks at every new boundary.

## Follow-ups

The extended campaign and final ordinary reactor validation passed, including the
new independent factory-slot model; diff whitespace checks passed. Commit this WIP
unit and continue immediately. Full source-to-JVM, packaging, session and release
gates are required before user-facing finalization.
