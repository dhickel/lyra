# Attachable Semantic Boundary Implementation

## Topic

How the attachable compile profile makes dispatch safe points explicit effect boundaries in canonical semantic flow without changing normal compilation.

## Source References

- `.internal-dev/plans/20260906-221525-complete-lyra-repl-with-trusted-module-linking-and-local/plan.md` (Phase 05 step 3)
- `.internal-dev/specifications/decisions.md` ("Attachable safe-point effect boundary, 2026-09-07")
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/SemanticFlowAnalyzer.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/ArrayIdentity.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/ValueFormula.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/api/ReplProfileEmissionTest.java`

## Key Takeaways

- Ownership facts and symbolic formulas are two different domains with a lossy bridge. `toFormulas` encodes every aggregate identity as a `ValueFormula.Declaration` and `fromFormulas` re-synthesizes the identity kind from module ownership alone. A new identity kind therefore needs either a formula-level discriminator (the `attachableBoundary` flag on `ValueFormula.Declaration`) or a predicate applied where formulas are materialized back into facts; identity kind alone is silently dropped on round-trip.
- Ordinary module-level value declarations never route through the call-site declaration-resolver callback. `resolveCallableDeclaration` returns empty for non-callable, non-external declarations, and `fromFormulas` synthesizes their facts directly. Conservative boundary facts must be applied at the `fromFormulas` materialization site, not only in the resolver callback.
- Module-level bindings referenced inside lambdas are captures (often shared cells). Capture materialization is a third transform point distinct from `readValue`: requirement checks at lambda creation consume capture formulas, so applying the boundary only to plain reads leaves creation-time checks with initializer facts.
- The mutation rejection surfaces through the existing machinery: `recordMutationOwnershipRequirement` records a symbolic requirement, `materializeCreationOwnershipRequirements` checks it at lambda creation, and `rejectImportedOwnershipRequirements` checks the caller-state-resolved transfer at call sites. The attachable boundary only needs facts, not new check sites.
- The same-module callable ownership filter in `rejectImportedOwnershipRequirements` deliberately exempts facts owned by the callee's module. `AttachableBoundary` facts must survive that filter because "the binding belongs to the root" does not imply "the current value is root-owned".
- Initialization is never dispatchable, so initializer-time reads and direct top-level mutations keep exact local facts; the boundary only affects reads from lambda bodies, captures, and values propagated out of reads.
- Negative results to avoid re-testing: scalar writes, whole-binding replacement, private-state mutation, callable replacement and higher-order transfers all compile in attachable mode; public-@mut-aggregate element mutation via captures or calls is rejected with `LYC-RESOLVE-022`; normal mode is untouched.

## Project Relevance

Phases 06-07 reuse these conservative producer-backed facts when sessions bind root state and when generated safe points dispatch. Reconstruction from embedded debug sources must compile with the attachable profile to derive the same boundary facts.

## Open Questions

Whether Phase 07's finer safe-point granularity (per-function/per-call boundaries) justifies relaxing the blanket per-binding conservatism to boundary-reset flow states. Current conservative model is sound for the raw-array ABI and is the accepted baseline.
