# Context

Range values now have a source-to-JVM foundation. Iteration must preserve the
existing complete lambda-contract, callback identity and ownership semantics.
The owner confirmed that iter is a reserved built-in like match.

## Goal

Implement the accepted zero- and one-parameter callback forms without treating
iteration as a single abstract call or replaying range construction.

## In Scope

Both call spellings, compact/full/named callbacks, contextual element typing,
immutable callback parameters and capture retention, repeated callback effects,
empty traversal, checked runtime failures and existing cancellation safe points.

## Out of Scope

User generics, arbitrary overload declarations, unsigned ranges, floating ranges,
break/continue, lazy stream pipelines, collecting callback results and implicit
callback return-value dropping.

## Implementation Steps

1. Implement the confirmed reserved iter keyword and postfix boundary exception.
   Preserve the decision in language-core.md and decisions.md.
2. Give resolution and typing an exact selected callback contract. Compact
   lambdas cannot be typed only after resolution: SemanticResolver currently
   publishes complete signatures for every lambda. Range inference must also
   handle computed bounds and prior inferred range bindings without substituting
   a guessed I64 type for a known narrower callback contract.
3. Preserve ordinary argument evaluation order: construct/read the range and
   evaluate the callback once, then invoke the selected function exactly zero or
   one arguments per element. A callback is a value, including conditional or
   stored closure selections, not merely inline syntax.
4. Extend CallableSummaryCompiler, CallableSummarySolver/Set, canonical
   SemanticFlowAnalyzer and their validators with a finite repeated-call model.
   Include zero-iteration state and closure/aggregate aliases. Joining one call's
   writes alone misses callable or ownership changes exposed on later iterations.
   Reuse existing operation identities, limits and mutation projections; do not
   add broad unknown-value fallbacks or suppress invariants.
5. Add explicit typed IR and validate source/flow links. Emit a typed primitive
   loop, retain the callback value, and invoke its exact generated interface.
   Reuse safe-point emission at the loop backedge. Check successor existence
   before adding the step, including signed-long endpoints.
6. Add range snapshot/storage integration, host argument width validation and
   compositional source fuzz/model coverage as required by the completed feature.

## Validation

Nested Cartesian loops, both endpoint forms/directions, empty/wrong-direction
and equal-endpoint ranges, unaligned endpoints, zero steps, signed boundaries,
exact side-effect order, precomputed/reused ranges, callback arities and return
contracts, closures retaining per-invocation parameters, mutable captures,
aggregate aliases, imported callbacks, failed invocations, persistent sessions,
cooperative cancellation, generated artifacts and ordinary/extended fuzz suites.

## Exit Criteria

Both accepted iter forms execute correctly across applicable compiler/runtime
surfaces; positive/negative/boundary/runtime-failure and independent model checks
pass; specifications/knowledge/changelog agree; complete change is committed.
