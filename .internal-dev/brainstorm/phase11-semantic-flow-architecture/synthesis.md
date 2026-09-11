# Phase 11 semantic-flow architecture synthesis

## Prompt
Synthesize the fallback architecture, decomposition, and red-team findings after the engine-owned brainstorm failed before worker startup three times due malformed role-router JSON. Preserve disagreements and recommend a recovery architecture without implementing code.

## Source
Root synthesis of:
- `fallback-architecture.md`
- `fallback-subdomains.md`
- `fallback-red-team.md`
- authoritative phase-11 plan and language/backend specifications

## Findings

### Consensus

The recurring failures are not ordinary isolated defects. Resolver, TypeChecker, TypedSemanticProvenance, and InitializationAnalyzer each contain a partial abstract interpreter for overlapping concepts: aggregate identity, projection routes, binding alternatives, callable values, branch/coalesce state, captured-cell writes, and eager effects. The same behavior is represented differently in `SemanticResolver.java`, `InitializationAnalyzer.java`, and `TypedSemanticProvenance.java`. This representation drift explains why a repair passes one adversarial combination and the next exposes another.

The required fix is a **modest internal architectural restructuring**, not a monolithic replacement of the semantic pipeline:

1. Define a shared JVM-independent route/identity/flow algebra.
2. Preserve resolver/type-checker/provenance/eager-analysis phase boundaries.
3. Replace the fragmented eager/callable walkers with one bounded typed flow/effect evaluator.
4. Keep provenance independently skeptical and source-derived; share pure laws, not published type authority.
5. Deliver sequentially with focused gates and no concurrent edits to overlapping semantic files.

### Missing shared model

The kernel should define:

- `ProjectionPath`: typed `TupleMember(index)`, `ArrayIndex(index)`, and wildcard/unknown segments; exact overlap, selection, nesting, prefixing, and route-local replacement.
- `AggregateIdentityFact`: identity token, selected route, imported owner/export or fresh/local origin, source/scope witness; binding-local `@mut` remains separate.
- `BindingFlowState`: declaration/cell to current alternatives, with strong whole replacement, route-local replacement, selection, and conservative branch joins.
- `CallableSummary`: lambda candidates, positional parameter bindings, return formula (fresh, parameter projection, or joined result), captured writes, and source witnesses.
- `TransferResult`: value fact, next flow state, and bounded effects (eager cross-module reads/calls, captured-cell mutations, callable return flow).
- Pure recursive nil-context derivation for expected/peer structures through blocks, arrays, tuples, conditionals, and coalescing.

A route identity law is essential: exact sibling routes must not taint one another, unknown routes may conservatively overlap, whole replacement discards previous alternatives, selected replacement affects only selected descendants, and branch joins union reachable alternatives. Callable and eager-value recursion guards must remain distinct.

### Which findings are true

True contract requirements:

- imported aggregate identities cannot launder mutation authority through aliases, narrowing, coalescing, containment, identity calls, higher-order parameters/results, or selected projections;
- nested arrays can mutate through an authorized `@mut` root, including a tuple root, while tuple fields themselves are not assignable;
- flow is source-order and path-sensitive enough that earlier calls do not see later replacements, known closure effects persist after invocation, and branch/coalesce alternatives are retained;
- eager value cycles reject, legal function-only signature recursion remains permitted, and known direct/namespace/callable/higher-order aggregate paths preserve dependencies;
- then-only conditionals return Unit while their bodies retain independent types/effects;
- nested/block/composite nil gets a base only from an expected or peer structural context, and bare/non-nil `#NIL` remains invalid;
- final published graphs are immutable, source-complete, and initialization-plan-consistent.

Likely overreach or phase-12 concerns:

- `eq?` returns Bool and does not itself carry aggregate identity;
- a monolithic general effect/ownership solver is not required;
- exact relational proof of every branch condition is not required when conservative may-state joins are sound;
- a package-private bootstrap graph with an empty plan is acceptable if it cannot be externally published; final graphs must be canonical;
- complete IR cell/schedule/control-flow encoding belongs to phase 12;
- unknown selectors can conservatively act as wildcards rather than requiring impossible precision;
- arbitrarily deep nil inference beyond a documented structural rule should not become an unbounded acceptance target.

### Disagreement requiring owner decision

The specifications contain tension over imported immutable aggregates. `language-core.md:115` says mutation requires an `@mut` symbol and an immutable array binding may initialize a new `@mut` binding; `:117` specifically prohibits an importing module from mutating an exported `@mut` binding. Current code protects every imported aggregate identity. The owner must decide whether:

A. every aggregate identity crossing a module boundary remains protected in the importing module; or
B. only an identity originating from an exported `@mut` binding is protected, while an imported immutable aggregate may be rebound to a local `@mut` binding.

This cannot safely be guessed during implementation.

Other decisions:

- exact current-route precision for statically known unconditional replacement versus allowed conservative rejection for unknown paths/callables;
- bounded recursive nil-inference boundary for nested conditional/aggregate siblings;
- whether function-only mutual recursion remains legal when invoked during initialization so long as no eager value is read;
- whether semantic assertions must be runner-discovered rather than relying on manual `main()` entry points.

## Options

| Option | Decision |
|---|---|
| Continue local map/set patches | Reject as the primary strategy; retain only for isolated defects after the kernel exists |
| Shared route/identity/flow algebra | Adopt |
| One monolithic global semantic interpreter | Reject; use only a bounded typed evaluator for flow/effects |
| Sequential phase-11 subdomains | Adopt; required to control overlap and review |
| Publish flow facts publicly | Reject; keep internal or phase-local immutable side artifact |

## Trade-offs

Recommended sequential ownership:

1. **11A exact aggregate selector/ownership algebra**: route laws, identity facts, imported ownership, mutation diagnostics, sibling/replacement behavior. Own route symbols in `SemanticResolver.java`; add focused ownership tests.
2. **11B binding/cell/call flow**: strong updates, branch/coalesce joins, captured mutable cells, known-call effects, source-position callable candidates. Own resolver flow orchestration after 11A.
3. **11C contextual nil/Unit**: one pure recursive structural derivation shared by TypeChecker and provenance mirror; then-only Unit and composite/block nil. Own TypeChecker contextual logic and matching provenance utilities.
4. **11D bounded higher-order/eager evaluator**: one typed evaluator consuming shared flow facts, positional call bindings, aggregate projections, returned values, persisted cell state, source order, recursion guards, dependency edges; SCC/topological ordering remains separate. Own `InitializationAnalyzer.java`.
5. **11E final sealing**: immutable graph publication, complete indexes, source provenance, canonical plan validation, forged artifact rejection. Own `TypedSemanticGraph.java` and remaining provenance integration.

Each gate should assert exact source spans/related spans, no partial artifacts, deterministic repeatability, and both positive/negative cases. Freeze the existing `Domain11SemanticTest` as smoke integration; add focused test files per subdomain instead of continually extending one mixed monolith. Test helpers must select declarations by identity, assert full diagnostic shape, and ensure runner discovery.

File ownership must be sequential because `SemanticResolver.java` and `TypedSemanticProvenance.java` have unavoidable overlap. `InitializationAnalyzer.java` must consume, not reconstruct, route laws. Phase 12 must wait until 11A-11E pass and must consume frozen facts rather than reinterpret syntax.

## Open Questions

1. Which imported immutable aggregate policy (A or B) is authoritative?
2. What conservative behavior is required for unknown callable/selector values?
3. What is the exact permitted recursive nil-inference boundary?
4. Is function-only recursion legal when called during initialization without eager value reads?
5. Should phase-11 semantic tests be registered as ordinary runner tests or explicitly wired by build configuration?
6. Where should the internal immutable flow artifact live: resolver graph, typed graph, or private analysis handoff?

## Recommended Next Step

Do not launch another broad “complete phase 11” repair agent. First settle the five owner decisions above. Then implement 11A and its algebra-law tests, gate it independently, and proceed sequentially through 11B, 11C, 11D, and 11E. This converts the current adversarial whack-a-mole loop into bounded contracts with explicit state ownership and lets phase 12 begin only after semantic flow is frozen.
