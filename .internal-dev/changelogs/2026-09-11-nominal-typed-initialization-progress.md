# Nominal typing and initialization progress (implementation continues)

## Date

2026-09-11

## Git Commit

`876c4775b6a3f217b2eacf524cf5fa01a5318052` (baseline).

## Change Summary

Added typed nominal declarations, construction, field selection/mutation, and a
producer-issued definite-initialization certificate tied to exact initializer
trees. Added corresponding closed IR operations and deferred instance-initializer
edges. Initial compiler heap snapshots and symbolic nominal projections preserve
top-level object aliases, immutable self captures and saved callable slots.

This is an unfinished feature checkpoint, not backend or session completion.

## Files

- Compiler TypeChecker, NominalInitializationProof, typed graph/provenance and IR.
- Semantic flow heap/reference records, callable projections/transfers and sealing.
- Resolver contextual Fn assignment and exact selected-member call links.
- Nominal semantics/type tests, exhaustive IR source inventory and public API negative.
- Active nominal plan, knowledge, coverage and self-review records.

## Behavioral Impact

Focused source-to-typed/IR tests cover required/default struct fields, class
construction and methods, saved versus replaced callable slots through aliases,
read-before-initialization, immutable duplicate initialization, incomplete receiver
publication/invocation and zero-trip callbacks. Independent seeded bit-set models
check constructor initialization across branches. Bare type names are not instances;
capitalized array bindings remain ordinary indexable values. Nominal canonical
spellings are cached, removing repeated hashing without a benchmark claim.

## Specification Impact

Accepted language contracts are unchanged. Implementation coverage advances from
resolver-only checks to focused typed/IR behavior. No runtime representation is
selected by the compiler-only heap maps: actual objects still require exact typed
JVM fields, schema authentication, object authorities and session linkage.

## Validation

Focused nominal and existing typed/IR checks passed during development. A full
reactor run passed before the final sealing/negative-test additions. The extended
`tools/fuzz-language.sh -q` campaign passed with 1,800 cases per seed, including
5,400 independent branch-initialization cases and existing language/runtime fuzz.
The final full reactor `mvn -q test -Dlyra.nominal.initialization.cases=1800`
also passed after the constructor-before-fields ordering regression was added;
ordinary `mvn -q test` passed during validation. `git diff --check` passed. The older
public-API negative incorrectly expected an empty class to fail; it now asserts
failure for a genuinely uninitialized class. No positive execution oracle accepts
unsupported emission. No release audit, graphical or independent review claim.

## Risks

General constructor-call summary transfer remains explicitly unfinished, as do
contextual replacement self, complete imported/cyclic/repeated heap effect handling,
all malformed publication cases, nominal JVM mapping/emission, runtime schemas,
object equality/authority, deterministic artifacts and persistent session support.
The complete feature remains an active goal. These tests do not prove executable
or persistent nominal support.

## Follow-up Items

Continue the implementation immediately after committing this validated checkpoint.
Finish semantic transfers and exact backend/runtime/schema boundaries, then retained
sessions and source-to-JVM qualification; run the release gate before final release.
