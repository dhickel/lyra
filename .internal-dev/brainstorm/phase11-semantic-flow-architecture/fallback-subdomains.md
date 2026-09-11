# Phase 11 semantic-flow subdomain findings

## Prompt
Decompose recurring phase-11 semantic-flow failures into sequential, independently gated implementation domains.

## Source
Fallback read-only max-thinking subdomain analyst. Inspected the phase-11 plan, language/backend specifications, current semantic implementation and tests.

## Findings
The contract requires binding-local mutation, aggregate identity through aliases/captures, source-order replacement, eager left-to-right evaluation, legal function cycles, and complete immutable artifacts. Current resolver state is private (`SemanticResolver.java:169-178,4202-4481`); analyzer state uses a different `CellFlow`/route model (`InitializationAnalyzer.java:47-65,2617-2741`); TypeChecker and provenance duplicate nil/Unit derivation. This split causes semantic drift. The current `Domain11SemanticTest` is a large mixed monolith; test methods manually invoke `main`, helpers often check only the first diagnostic code or first same-named declaration, and private flow state is not directly observable.

## Options

The practical delivery option is five sequential subdomains backed by one shared internal route/flow algebra, not concurrent edits to the same semantic files. A monolithic global interpreter or independent patching of every walker is not recommended.

## Trade-offs

### 1. Exact aggregate selector identity and ownership
Inputs: resolved declarations/imports/exports/mutability/spans and projections. Output: typed selector paths, ownership facts, mutation diagnostics and related spans. Invariants: paths distinguish tuple members/array indices; empty means whole aggregate; unknown is wildcard; selection composes; route replacement affects only overlaps; siblings do not taint one another; tuple fields are not assignable but nested arrays can mutate through an `@mut` root. Gate resolver-only fixtures for direct/nested/wildcard/sibling/replacement ownership and no partial artifacts. Own route symbols in `SemanticResolver.java`; add `Domain11AggregateOwnershipTest`.

### 2. Flow-sensitive binding/cell/call state
Inputs: route algebra, source-ordered declarations/replacements, captures, known calls. Output: current alternatives, callable candidates, captured-cell effects. Invariants: strong whole replacement; route-local replacement; branch/coalesce joins; lambda creation has no body effect; known closure invocation updates shared cells; source-position-sensitive candidates; distinct recursion guards. Gate post-call ownership, branch state, source ordering, and captured mutation. Own resolver flow orchestration after subdomain 1.

### 3. Contextual nil and Unit typing
Inputs: expected types, blocks, branches, coalescing, aggregate literals, contracts. Output: exact recursive nil context and typed contracts. Invariants: bare nil needs nilable context; nonnil context cannot legitimize nil; nested array/tuple/block/conditional context derives from peer/expected shape; coalesce returns nonnil base; then-only conditionals are Unit while body retains its own type; predicate bindings are immutable narrowed bases. Gate child types, contracts, conversions, provenance, and no partial failures. Own TypeChecker contextual methods and contextual provenance mirror.

### 4. Higher-order callable/eager initialization analysis
Inputs: fully typed graph, links/captures/routes, current candidates, positions. Output: immutable dependencies, order, cycles, callable discovery. Invariants: positional argument binding; identity-preserving route propagation; fresh producers do not inherit imported identity; complete selector queues; tuple sibling filtering; later assignments cannot affect earlier calls; known closure effects persist; active eager re-entry is a value cycle; function-only SCCs are legal. Gate all direct/namespace/callable/aggregate/higher-order paths, source paths, deterministic order, and cycle diagnostics. Own `InitializationAnalyzer.java` and consume shared facts.

### 5. Final provenance and compatibility sealing
Inputs: complete resolved/typed graph, flow facts, contracts, mutations, failure sites, canonical plan. Output: immutable graph and phase-12 handoff. Invariants: exact membership, IDs, spans, scopes, links, child order, conversions, mutation/failure indexes, source-derived inferred typing, and canonical plan; forged graphs reject. Gate valid reconstruction/determinism and forged route/scope/span/conversion/plan rejection. Own `TypedSemanticGraph.java` and remaining provenance validation last.

## Open Questions

- Should aggregate provenance be an immutable internal side artifact?
- Does unknown selector mean any element in every pass?
- How should cross-module mutable function replacement be represented?
- Must unknown higher-order calls conservatively reject?
- Should nil narrowing be explicit nodes or remain in conditional/coalesce types?
- Should semantic assertions be registered with the test runner rather than relying on `main()` methods?

## Recommended Next Step

Dependency order: `1 route/ownership -> 2 flow/cells/calls -> 3 nil/Unit -> 4 eager evaluator -> 5 sealing`. Freeze the current mixed smoke corpus, add focused files (`Domain11AggregateOwnershipTest`, `Domain11FlowStateTest`, `Domain11ContextualTypingTest`, `Domain11InitializationFlowTest`, `Domain11SealingTest`), and assign one owner per subdomain sequentially. Phase 12 must consume frozen flow facts and must not reconstruct ownership/callable flow/nil/effects from syntax.
