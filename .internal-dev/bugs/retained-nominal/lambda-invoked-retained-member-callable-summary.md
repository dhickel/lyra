# Lambda bodies cannot directly invoke or return a callable read from a retained nominal member

## Summary

A lambda whose body directly reads a callable-typed retained nominal member, either to invoke it (`(box:.f)`, `box::f[]`) or to return it (`(=> || box:.f)`), fails the canonical semantic flow analysis with `MISSING_CALLABLE_FACT` (`typed callable reference has no recoverable callable alternative` / `function value has no canonical callable identity`) as an internal compiler failure instead of compiling. The same member read works at the root level and inside a lambda once bound to a local first.

## Scope

- `lyra-compiler` retained callable-summary flow: `SemanticFlowAnalyzer` / `CallableSummarySolver` summary materialization for lambda bodies over retained nominal member reads of function type.
- Reproduces in both source-local and importing-graph retained sessions; it is independent of issue #7 route authority and of the importing `sourceLocal` classification.

## Reproduction

```text
generation 1 (session submit): class Box { let @pub f :Fn<;I32> = (=> || 7I32) }
generation 2: let box :Box = :Box[]
generation 3: { let cap :Fn<;I32> = (=> || (box:.f)) (cap) }
```

Observed: `LyraCompilerBugException` with `MISSING_CALLABLE_FACT` during type checking (no structured diagnostic). The same shape fails with `box::f[]` direct-call spelling, with an importing producer, and with `let mk :Fn<;Fn<;I32>> = (=> || box:.f) (mk)`.

Working shapes: root-level reads (`let saved :Fn<;I32> = box:.f (saved)`), and lambda bodies that read a local binding holding the member read (`{ let held :Fn<;I32> = box:.f (=> || (held)) }`).

## Expected

A lambda body should recover the retained member read's canonical callable identity exactly as a root-level reference does, so invocation and return of a field-derived callable inside a new lambda compile under ordinary flow rules.

## Actual

`MISSING_CALLABLE_FACT` escapes the type checker as a compiler invariant, aborting compilation of otherwise legal source.

## Evidence

Discovered while exercising issue #7 parameter/return propagation in
`NominalSessionTest` and mirrored as GitHub issue #15. Probes confirmed the failure in a clean source-local
session (`class Box { let @pub f :Fn<;I32> = (=> || 7I32) }`) with no
route-delegate or importing-graph involvement, so the defect predates and is
orthogonal to the issue #7 authority changes.

The retained `box` binding enters `CallableSummaryCompiler` as an external
root declaration formula. A direct member read projects that formula to the
exact nominal-member route and a `Fn` result, but the solver previously did
not classify this non-root declaration formula as requiring caller-time
substitution. Direct calls therefore failed summary target validation, while
direct returns reached semantic materialization as a function formula with no
canonical identity. The repair preserves that exact route, defers only the
projected callable declaration, resolves the root from canonical caller flow,
and permits only an exact non-root `ObjectReference` intermediate that the
summary object resolver rechecks against the current certified heap/schema
before recovering the callable.

Focused `NominalSessionTest#directRetainedMemberCallableReadsSurviveLambdaSummaries`
coverage now passes all three importing-session shapes: S-expression invocation
of the directly read member from a capturing lambda, direct return of the member
callable, and `::` direct member invocation. Existing raw/delegated
anti-laundering, saved/captured/parameter/return propagation, replacement,
nested-leaf rejection, and imported-graph `sourceLocal` authority selectors
remain green.

## Impact

- Before the repair, capture and return shapes that read a callable nominal member directly inside a new lambda body were blocked; binding the read to a local first was the only workaround.
- The defect was fail-closed: no wrong value was produced, but valid source failed with an internal compiler failure instead of executing or receiving a structured diagnostic.

## Status

Fixed in the current worktree as an in-scope Tranche 3 issue #7 compiler
repair. Focused regressions and authority-boundary selectors pass. The record
remains active until the surrounding uncommitted Tranche 3 work is committed
and its separately required broader qualification is completed.

## Next Action

Run the accepted tranche's broader validation and synchronize GitHub issue #15
when commit evidence is available. Do not archive this record before that
closeout.
