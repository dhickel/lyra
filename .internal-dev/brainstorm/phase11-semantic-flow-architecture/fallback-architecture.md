# Phase 11 semantic-flow architecture findings

## Prompt
Analyze whether recurring Lyra phase-11 semantic-flow failures require architectural restructuring, and identify a shared model without implementing code.

## Source
Fallback read-only max-thinking architecture analyst. Inspected the phase-11 plan, language/backend specifications, `SemanticResolver.java`, `TypeChecker.java`, `TypedSemanticProvenance.java`, `TypedSemanticGraph.java`, `InitializationAnalyzer.java`, and `Domain11SemanticTest.java`.

## Findings
Phase 11 combines aliasing, mutation, calls, captures, cycles, and nil contextual typing. The implementation has multiple partial flow engines: resolver-side provenance/callable flow (`SemanticResolver.java:144-174,1753-2100,3144-3442`), typed eager-flow analysis (`InitializationAnalyzer.java:45-141,211-470,2617-2730`), and independent source reconstruction (`TypedSemanticProvenance.java:291-551,835-894,1037-1730`). Resolver aggregate provenance remains private mutable `DeclDraft` state and is not published by `ResolvedDeclaration.freeze()` (`SemanticResolver.java:3949-4013,4202-4267`); the analyzer reconstructs identity from typed expressions. Graph construction also recomputes canonical initialization analysis (`TypedSemanticGraph.java:63-130,243-260`; `TypeChecker.java:2827-2900`).

The repeated hidden failures indicate semantic-model drift, not only missing guards. The same scenarios require coordinated fixes in resolver, eager analyzer, and provenance verifier.

Duplication:
- Aggregate identity is separately represented by resolver `Use.importedAggregateOrigins`/`AggregateImportOrigin`, TypeChecker mutation-root traversal, provenance traversal, and analyzer alias/route maps. Resolver uses `Optional<Integer>` route segments while analyzer has separate tuple/array route logic.
- Callable discovery is duplicated between resolver syntax walkers (`callResult`, `collectInvocationLambdas`) and analyzer walkers (`callableCandidates`, `returnedCallableCandidates`, `functionValueLambdas`, `callTargetLambdas`, aggregate projections).
- Branch/cell snapshots differ: resolver tracks aggregate origins/callable candidates/assignments while analyzer tracks `CellFlow`/`AssignedValue`/routes.
- InitializationAnalyzer is already an abstract interpreter in practice, but its walk, callable discovery, aggregate projection, and return-flow logic are fragmented.
- TypeChecker and TypedSemanticProvenance duplicate nil/branch derivation; skepticism is necessary, but pure rules should be shared.

## Options

1. Minimal local map/set patches: lowest churn, but repeats cross-product fixes and preserves drift.
2. Shared immutable route/identity/flow algebra plus transfer functions: centralizes selectors, replacement, joins, and parameter binding while preserving phase boundaries.
3. One monolithic semantic abstract interpreter replacing the passes: shared state, but entangles resolution, bidirectional typing, provenance, and eager effects and overreaches the plan.
4. Sequential subdomains only: improves delivery and review but does not itself remove duplicated representations.

## Trade-offs

The recommended combination is option 2 plus a bounded typed flow/effect evaluator, delivered as sequential subdomains. Do not replace the bidirectional checker with a global solver or expose flow as public API. A shared kernel should model typed projection paths (`TupleMember`, `ArrayIndex`, wildcard), aggregate identity/ownership facts, binding alternatives, callable summaries, and transfer results `(value fact, next state, effects)`. Resolver can use an adapter for partial shapes; TypeChecker remains responsible for types and does not execute bodies; InitializationAnalyzer becomes one evaluator; cycle SCC/topological analysis remains separate; provenance stays independently source-derived and skeptical.

Suggested flow:

```text
resolved syntax -> shared flow facts -> bidirectional typing + pure nil derivation
-> typed graph -> bounded typed flow/effect evaluator -> dependency graph/SCC
```

Use strong whole-binding replacement, route-local replacement, may-state branch joins, and positional parameter binding. Keep callable recursion guards distinct from eager-value recursion guards. Avoid repeated full canonical analysis where one analysis result can be validated.

## Open Questions

- Should routes be a typed path algebra or shape-annotated integers?
- Should fresh/local and imported identities share a token, or should ownership remain separate?
- What is the policy for unknown callable candidates?
- Should immutable flow facts be an internal side artifact, part of the resolved graph, or part of the typed graph?
- Should branch joins remain may-state unions, or preserve relational branch conditions?
- Should provenance share pure derivation utilities only, or compare against a canonical semantic summary?

## Recommended Next Step

Freeze current tests as integration smoke coverage, define and law-test a shared route/identity/flow kernel, then implement sequentially: exact aggregate ownership, resolver flow/cells, nil/Unit derivation, one eager evaluator, and final graph/provenance sealing. Do not add more independent route/call walkers.
