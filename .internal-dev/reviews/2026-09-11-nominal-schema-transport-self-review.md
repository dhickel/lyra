# Nominal compiler schema transport self-review

## Scope

Validated-IR schema conversion and transport to embedded facade and packaged
artifact metadata. This is a local self-review, not executable-object qualification.

## Findings

- Conversion starts from a validated closed IR schema environment, preserving
  origin identity and all member/constructor contracts. Runtime records remain
  compiler-independent; nominal references are cached during projection.
- Each converted value contract is checked for exact canonical spelling parity,
  including nested qualifiers. Schemas are validated again by the runtime model.
- Both metadata creation paths receive the environment before export parsing and
  bind its complete contract into the artifact revision. Empty environments leave
  ordinary artifact encodings and revisions unchanged.
- Source-derived and independent seeded field models verify recursion, nullable
  arrays, exact constructor order, visibility and method-slot mutability.
- Generated closures still need schema-aware live signature parsing when their
  contracts contain nominal types. Object class/member plans and emission are also
  missing; metadata wiring alone is not executable nominal support.

## Risk Assessment

No nominal emitter failure counts as a passing execution test. Schema transport
does not complete class inventory, producer authority, runtime object equality or
session linkage. Remaining control-sensitive semantic heap work stays open.

## Recommendations

Use the originating artifact's closed schema environment for nominal-bearing runtime
signatures. Keep schema-free parsing for legacy contracts. Implement and independently
validate generated object/member inventories before source-to-JVM qualification.

## Follow-ups

Extended fuzz and final ordinary reactor validation passed; diff checks passed.
Commit validated transport, then continue runtime contract linkage and direct object
emission without treating this checkpoint as completion.
