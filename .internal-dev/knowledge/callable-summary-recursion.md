# Recursive Callable Summary Normalization

## Topic

Finite-domain normalization for recursive callable summaries that retain captured-cell writes and ownership evidence.

## Source References

- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/CallableSummarySolver.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/CapturedCellWrite.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/OwnershipRequirement.java`
- `.internal-dev/bugs/20260906-recursive-captured-cell-summary-limit/report.md`
- `.internal-dev/specifications/backend-runtime.md`

## Key Takeaways

- A recursive SCC transfer is one abstract invocation. Re-appending its already-solved write trace on every fixed-point iteration makes a one-source-write loop grow until the finite write limit is reported.
- Recursive write and ownership operations are identified without caller sequence or substituted value. The key retains the target cell/parameter, route, source span/source site and operation kind, so distinct source writes targeting one cell remain distinct.
- The first source-ordered occurrence is retained; later recursive occurrences join their `FormulaAlternatives` into that occurrence. This bounds repeated recursion while preserving value/capture snapshots and still checks formula and write limits.
- Non-recursive transfers retain the existing caller-event rebasing and duplicate source-order guards. A raw or transferred event sequence collision remains an inconsistent summary rather than being merged.
- The same normalization is required for ownership obligations. Raw source sites remain ordered and distinct; repeated recursive obligations for one source site join their substituted values.

## Project Relevance

The exact captured scalar loop from issue #2 now compiles through the typed summary API with `maxWrites=1`, reaches a fixed point, and retains the shared-cell capture identity. Distinct recursive writes and ownership sites remain visible and fail closed when an intentionally smaller finite domain cannot represent them. The initialized-generation runtime regression also passes when run alone.

## Open Questions

The repository worktree contains concurrent session/runtime changes. Full `mvn test` currently has unrelated failures in session linkage/publication and imported computed-call flow paths; those are outside this solver change and owned by the concurrent work. No specification contract was changed.
