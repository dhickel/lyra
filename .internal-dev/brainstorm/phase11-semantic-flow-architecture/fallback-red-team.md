# Phase 11 semantic-flow red-team findings

## Prompt
Red-team recurring phase-11 validator findings: classify true requirements, over-strict interpretations, phase-12 concerns, and decisions needed to stop counterexample expansion.

## Source
Fallback read-only max-thinking contract/red-team analyst. Inspected authoritative specifications, phase-11 plan, semantic implementation, and tests.

## Findings
Most findings are true contract requirements, but the current implementation needs precision rather than an unbounded global effect system.

## Options
- Treat all imported aggregate identities as protected, or protect only identities from exported `@mut` bindings.
- Require exact current-route selection for statically known unconditional replacements while permitting conservative rejection for unknown indices/callable identities, or reject all uncertain paths.
- Bound recursive structural nil inference to explicit/peer-derived shapes, or broaden it across arbitrary sibling shapes.
- Keep semantic assertions as manually invoked smoke tests, or register them reliably with the test runner and split them into focused matrices.

## Trade-offs

### Confirmed requirements
Binding-local mutation/import ownership (`language-core.md:113-117`); arrays retain identity through aliases and tuples; whole/selected replacement is flow-sensitive; known identity-preserving and higher-order calls preserve relevant provenance; lambda creation does not execute bodies while known invocation can; active eager-value and callable recursion use separate guards; bare nil cannot infer a base type; final artifacts are immutable/complete/plan-consistent; then-only conditionals are Unit but retain independent body type/effects; source-order replacements are required; eager value cycles reject while declaration-only function SCCs remain legal.

### Likely overreach or phase boundary
`eq?` returns Bool and does not itself carry aggregate provenance. A monolithic global interpreter/general effect system is not required. Exact relational proof of every branch condition is not required; sound possible-state joins are enough. The internal bootstrap graph with an empty plan may remain package-private; only final graph publication needs canonical-plan validation. Full IR cell/schedule/control-flow validation is phase 12. Arbitrary deep nil inference is partly under-specified; choose a bounded structural rule. Unknown selectors can conservatively wildcard.

### Material contract decision
The specification has tension around imported immutable aggregates: `language-core.md:115` says mutation requires an `@mut` symbol and an immutable array binding may initialize a new `@mut` binding, while `:117` explicitly prohibits mutation of an exported `@mut` binding. Current code protects every imported identity. The owner must choose whether every aggregate crossing a module boundary is protected or only identities originating from exported `@mut` bindings.

### Counterexamples to retain
- Mutable tuple root nested array mutation: accept through `@mut` root; tuple-field assignment remains illegal.
- Imported aggregate via alias/narrowing/coalesce/identity/higher-order call: reject laundering.
- Branch/coalesce joins: retain all reachable ownership states.
- Source-order replacement: earlier calls cannot see later values; unconditional current replacement removes stale candidates.
- Function-only recursion: legal when no eager value read; reject when a value is eagerly read.
- Then-only branch: outer type Unit, branch has its own type.
- Nested/block/composite nil: accept only where expected/peer structure provides a base; bare nil remains invalid.

## Open Questions

1. Imported immutable aggregate policy: protect every imported identity or only exported `@mut` identity?
2. Required precision for unconditional replacements versus allowed conservative rejection for unknown paths/callables?
3. Nil inference boundary for nested conditionals and homogeneous aggregate elements?
4. Is function-only mutual recursion legal when called during initialization if no eager value is read?
5. Should all semantic assertions be runner-discovered, not only manually invoked?

## Recommended Next Step

Make those five decisions explicit, then implement the shared route/flow kernel and bounded evaluator in sequential gates. Close phase 11 only after exact/wildcard ownership, aliases, narrowing, coalescing, identity/higher-order calls, branch/cell joins, source-position candidates, function-only versus eager cycles, then-only Unit, recursive nil, immutable final graph, and canonical plan validation pass representative depth-0/1/2 matrices. Do not require an unbounded hand-written matrix; require algebra-law tests and bounded recursive implementation coverage. Keep phase 12 limited to closed IR encoding/validation.
