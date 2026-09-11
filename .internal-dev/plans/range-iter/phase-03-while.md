# Context

The owner authorized while with the same function/callback approach as iter.
Reserved keyword, both call spellings and parser boundaries are implemented.
The repeated-effect machinery described in phase-02 remains unfinished.

# Goal

Execute a pre-test loop with exact Fn<;Bool> and Fn<;Unit> callbacks, returning
Unit without new return syntax or implicit value dropping.

# In Scope

Compact/full/named/computed callbacks; shared mutable captures; construction
once in argument order; predicate/action alternation; normal and failing effects;
constant-stack execution; application/session cancellation safe points.

# Out of Scope

Predicate input generators, explicit state threading, collecting results,
truthiness coercion, nonlocal return, break/continue and do-while syntax.

# Implementation Steps

1. Supply exact contextual callback signatures in resolver and checker, retaining
   source identities for unqualified bracket and parenthesized calls.
2. Use shared repeated-call summary/flow analysis from phase-02. Preserve the
   initial predicate effects even for zero actions and the terminal predicate's
   effects after repeated actions. Include aggregate/callable capture changes.
3. Add certified typed IR, source provenance validation and JVM loop lowering;
   hold the two evaluated callback values in locals and emit backedge safe points.
4. Add source-to-loaded-JVM and session execution tests, independent loop oracles
   and generated histories, failure source frames and cancellation tests.
5. Run mvn test plus extended fuzz, review documentation, and commit completion.

# Validation

False initially; one/many actions; predicate side effects; callback construction
order and exactly once; full/compact/stored/conditional callbacks; nested loops;
shared mutable scalar, aggregate and callable bindings; callback variable
replacement versus retained callable identity; wrong arity/result/qualified
contracts; failures in construction/test/action; long constant-stack repetition;
persistent sessions and cooperative cancellation. Grammar-only tests do not
prove these execution contracts.

# Exit Criteria

All semantic, emitted execution, failure and persistence tests pass alongside the
shared iter implementation. Until then this is an explicitly unfinished phase.
