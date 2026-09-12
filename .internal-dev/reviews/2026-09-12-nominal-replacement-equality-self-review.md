# Nominal replacement and equality self-review

## Scope

Contextual receiver capture for direct method replacement lambdas and direct JVM
execution of struct/class equality.

## Findings

- Resolution creates a distinct contextual SELF only for a lambda directly assigned
  to a method slot and types it from the once-resolved target receiver.
- Nested lambdas retain that receiver through ordinary capture chains. Lexical
  private access remains independent and external replacements cannot gain it.
- Assigning an already-created callable does not rebind its captures or receiver.
- Every top-level equality expression that can reach a struct allocates a fresh
  traversal context. Generated struct methods reuse it through arrays, tuples,
  recursive fields and match-pattern equality.
- Runtime validation precedes private field traversal and checks both operands,
  lifecycle/producer authority and exact nominal schema.
- Class equality and identity operators use reference identity. Struct equality is
  structural and structs remain outside the identity-bearing operator domain.
- Generated plans expose exactly one typed structural method for each struct and
  none for classes.

## Risk Assessment

Medium. Focused executable tests cover selection order, nested capture, saved
callables, mutation between comparisons, nil, cyclic graphs, nested aggregates,
match and class leaves. Artifact packaging/Java construction and persistent-session
nominal values are still separate completion gates.

## Recommendations

Keep the equality context expression-local and preserve direct typed field access;
using generated public getters would change privacy and lifecycle semantics. Treat
contextual SELF as compiler evidence, never as source-visible private authority.

## Follow-ups

Complete loader/package inventory and Java facade construction coverage, then extend
persistent session storage, snapshots, reload and producer-lifetime tests for nominal
objects before the final Phase 24 release audit.
