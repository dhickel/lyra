# Topic

First-class range implementation and pending iter integration.

## Source References

- Owner conversation accepting enclosed range values and a built-in higher-order
  function named `iter`, supporting zero- and one-parameter Unit callbacks.
- `.internal-dev/plans/range-iter/plan.md` (active work and validation status).
- `language-core.md`, ranges and iteration.
- GrammarMatcher.parsePostfix, SemanticResolver, TypeChecker,
  CallableSummaryCompiler, CallableSummarySolver and CallableSummarySet.

## Key Takeaways

- Both `|| body` and `|x| body` already parse as compact lambdas. They require a
  complete expected Fn contract during resolution; range-dependent callback
  specialization therefore cannot be implemented only in bytecode emission.
- Ordinary `::name[...]` after another expression is a receiver method suffix,
  even across whitespace/newlines. `::match[...]` has an explicit reserved-word
  exception. The owner confirmed the same reserved-word policy for iter.
- Numeric lexing must stop before both `..` and `...`, at both decimal-point
  checks. Range type arguments must also preserve existing close-before-equals
  tokenization and canonical compiler/runtime type parity.
- A new expression needs more than a visitor case: source mutation topology,
  typed source provenance, shape checks, closed IR validation, ABI planning,
  runtime type parsing and the emitted-type capability guard all apply.
- Callback execution must model zero or repeated invocations. Treating iter as
  one call is unsound for captured cells, aggregate aliases and callable values.
  The existing callable-summary transfer and canonical flow machinery must retain
  these effects and ownership facts before bytecode emission is claimed complete.

## Project Relevance

The current worktree has executable range construction and Java export tests.
It does not yet implement iter or range persistence/snapshots. Do not mistake
passing range construction tests for completion of the owner request.

## Open Questions

The iter reserved-name decision is settled; execution integration remains open.
Initial signed domains are the announced default; unsigned descending ranges
would require a separate signed-step contract.
