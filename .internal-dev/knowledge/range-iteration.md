# Topic

First-class signed ranges and executable reserved iter/while callback loops.

## Source References

- Owner conversation accepting enclosed range values and a built-in higher-order
  function named `iter`, supporting zero- and one-parameter Unit callbacks.
- `.internal-dev/plans/.archive/range-iter/plan.md` (completed implementation).
- `language-core.md`, ranges and iteration.
- GrammarMatcher.parsePostfix, SemanticResolver, TypeChecker,
  CallableSummaryCompiler, CallableSummarySolver and CallableSummarySet.

## Key Takeaways

- Both `|| body` and `|x| body` already parse as compact lambdas. They require a
  complete expected Fn contract during resolution; range-dependent callback
  specialization therefore cannot be implemented only in bytecode emission.
- Ordinary `::name[...]` after another expression is a receiver method suffix,
  even across whitespace/newlines. `::match[...]`, `::iter[...]`, and `::while[...]`
  are rejected reserved-form spellings; the accepted bare bracket forms are
  `match[...]`, `iter[...]`, and `while[...]`. A narrow comma may disambiguate a
  following sibling `::` head, but commas are not general separators.
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
- While's zero-action path still invokes the predicate. Its effects, including
  those of the final false test, must reach the continuation. Callback values are
  constructed once, but their shared captures stay live across tests/actions.
- The existing backend optimizes direct self-tail calls, but simply adding a
  recursive source rewrite would require explicit source/provenance certification;
  it is not a free substitute for the shared callback-loop integration.
- Resolver range values must carry the range type, not merge the scalar bound
  ownership contracts into their result. Provisional numeric inference mirrors
  literal representability and known operand widths; do not default every
  inferred range to I64. Restrict ownership-projection inference fallback to
  the numeric/range cases it supports, or unrelated diagnostic ordering changes.
- Repeated callable summaries retain the selected function identities and solve
  shared-cell/capture/aggregate effects to a bounded monotone fixed point. Their
  symbolic post-loop tuple carries updated binding facts into the continuation;
  it is not a runtime allocation. Source/repetition and projection validators
  must certify this metadata, not treat the loop as a single ordinary call.
- Mutable function self-rebinding requires a real shared-cell capture for the
  rebinding target. Ordinary recursive self references retain the existing self
  path. Capturing all mutable self references instead causes recursive callable
  fact construction and breaks escaped recursive functions. Seed the lazy
  self-cell identity before constructing the self-rebinding closure's captures.
- Conditional/match branches returning different generated closure classes need
  casts to their declared Fn interface at the verifier merge. Casting every
  lambda changes unrelated artifact bytes and is unnecessary.
- JVM lowering retains evaluated arguments and authenticates selected callbacks
  before repetition. Iter uses primitive long cursor/step locals, with narrow
  argument adaptation; hasSuccessor is checked before adding the step. Both
  loops emit owner safe points and keep a constant stack. No iterator or
  per-iteration Unit result allocation is needed; callback allocations remain
  ordinary language behavior.
- Range values use exact width checks at host/session/traversal boundaries and
  immutable atomic RANGE snapshots, including inside aggregates. Display text
  uses signed decimal values and is not a source-replay format. Dynamic zero
  steps must be checked after all bounds evaluate and report a source-mapped
  LYR-ARITH failure.

## Project Relevance

Range construction, iter and while now execute through the validated compiler,
typed IR and Java 25 backend, including persistent sessions. Integration tests
exercise reused/nested ranges, callback identity and ownership, fresh invocation
captures, callback failures, zero-step failures and cancellation. The source fuzz
worker's loops mode uses an independent traversal/predicate-count oracle; extended
range/loop campaigns cover every signed width.

## Open Questions

Unsigned descending ranges would require a separate signed-step contract and
are not part of this implementation. No release/ABI migration claim follows
from this feature commit; the release audit remains a separate mandatory gate.
