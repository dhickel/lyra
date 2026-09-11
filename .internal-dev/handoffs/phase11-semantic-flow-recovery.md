# Phase 11 semantic-flow recovery handoff

## Context

Phases 1-10 are implemented in the current dirty worktree and have passed their gates. Phase 11 has substantial semantic behavior, but repeated independent max validation found combinatorial failures in aggregate ownership, callable flow, eager initialization, and nested nil typing. A broad repair agent was stopped when the user requested an architecture brainstorm.

Detailed brainstorm records:

- `.internal-dev/brainstorm/phase11-semantic-flow-architecture/fallback-architecture.md`
- `.internal-dev/brainstorm/phase11-semantic-flow-architecture/fallback-subdomains.md`
- `.internal-dev/brainstorm/phase11-semantic-flow-architecture/fallback-red-team.md`
- `.internal-dev/brainstorm/phase11-semantic-flow-architecture/synthesis.md`

## Objective

Resume phase 11 through bounded, sequential implementation gates rather than broad counterexample-driven patches. Close all non-deferred semantic requirements while preserving phase boundaries and preparing a stable handoff to phase 12.

## Settled Decisions

- Add a shared internal JVM-independent route/identity/flow algebra and one bounded typed flow/effect evaluator.
- Preserve separate resolver, bidirectional TypeChecker, skeptical source-derived provenance verifier, and eager dependency/SCC boundaries. Do not create one monolithic global interpreter.
- Protect every aggregate identity crossing a module boundary from mutation in the importing module, even after local `@mut` rebinding. Local mutability and imported ownership remain separate facts.
- Preserve exact known tuple/array routes and use wildcard/may-state behavior for unknown selectors/callables.
- Whole-binding replacement is a strong update; selected replacement is route-local; branch/coalesce joins retain all reachable alternatives.
- Recursively derive nil context through final block expressions, conditionals, arrays, and tuples only when expected or peer shape supplies the base. Bare/nonnil `#NIL` remains invalid.
- Function-only recursion remains distinct from eager value cycles.

## Constraints

- Preserve unrelated dirty/untracked state, including deleted prototype sources, `AGENTS.md`, and `.internal-dev`.
- No backend/runtime/CLI/public Java API, phase-12 full IR, user types/member methods, Java interop, loops, match, generics, or other deferred work.
- Keep immutable published artifacts, exact IDs/spans/links, structured diagnostics, and no partial semantic artifacts.
- Do not use blanket imported taint, stale historical candidate sets, or repeated independent route/call walkers.

## Scope

Phase 11 semantic flow only: aggregate ownership, binding/cell/call flow, contextual nil/Unit typing, higher-order eager initialization, and final immutable semantic sealing.

## Recommended Direction

Implement five sequential subdomains with explicit ownership and independent gates. Do not edit overlapping semantic files concurrently. Phase 12 must consume frozen flow facts and must not reconstruct ownership, callable flow, nil context, or eager effects from syntax.

### 11A — Exact aggregate route and ownership algebra

Create/test typed projection paths and aggregate identity facts. Centralize tuple-member versus array-index selection, wildcard overlap, nesting, route-local replacement, sibling noninterference, ownership witnesses, and imported-mutation diagnostics. Adapt resolver `Use` state first.

### 11B — Binding/cell/call flow

Use the 11A algebra for source-position-sensitive current binding state, strong whole/selected updates, branch/coalesce joins, shared mutable captures, known-call effects, callable parameter/result bindings, and function rebinding. Lambda creation must not execute its body; known invocation may update captured cells.

### 11C — Contextual nil/Unit

Define one pure recursive structural nil-context derivation used by TypeChecker and the contextual portion of provenance. Cover then-only Unit conditionals, final block expressions, nested arrays/tuples/conditionals, coalescing, and exact expected/peer typing.

### 11D — Higher-order eager evaluator

Reduce InitializationAnalyzer to one bounded typed flow/effect evaluator consuming 11A/11B facts. Bind positional call parameters, propagate aggregate/function return projections, persist known captured-cell state, enforce source-order candidate visibility, retain complete selector paths, distinguish callable recursion from eager-value re-entry, and emit deterministic dependency edges/order/cycles. Keep SCC/topological graph processing separate.

### 11E — Final sealing

Validate complete immutable graph membership, IDs, spans, scopes, links, child order, conversions, mutations, failure sites, source-derived inferred types, and canonical initialization plan. Reject forged/incomplete graphs. Phase 12 consumes frozen facts and does not reinterpret syntax.

## Recommended file ownership

- New internal route/flow types under `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic` or a tightly owned subpackage: 11A.
- `SemanticResolver.java`: 11A then 11B, sequentially only.
- `TypeChecker.java`: 11C.
- `TypedSemanticProvenance.java`: 11C contextual mirror, then 11E final validation.
- `InitializationAnalyzer.java`: 11D only after 11A-11C gates.
- `TypedSemanticGraph.java`: 11E.
- Freeze current `Domain11SemanticTest.java` as smoke coverage; add focused `Domain11AggregateOwnershipTest`, `Domain11FlowStateTest`, `Domain11ContextualTypingTest`, `Domain11InitializationFlowTest`, and `Domain11SealingTest`.

## Validation

Each subdomain must pass its own focused gate with full diagnostic shape (code, phase, primary span, related spans), no partial artifacts, and deterministic repeated analysis. The final phase-11 gate must include representative exact/wildcard selector depths, branch/coalesce joins, higher-order parameter/result paths, function reassignment/source order, legal function-only recursion versus eager cycles, nested/composite nil, immutable publication, and canonical-plan forgery rejection. Run `mvn -pl lyra-compiler clean test`, root `mvn clean verify`, and `git diff --check` after each relevant handoff.

## Open Questions

None of the currently identified decisions remain open. Unknown callable behavior and any later relaxation of imported immutable aggregate policy require a new explicit decision before implementation.
