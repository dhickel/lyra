# First-class ranges, iter and while

## Context

The owner authorized first-class `(start..end:step)` / `(start...end:step)`
ranges and a built-in higher-order function, subsequently named `iter`.
`iter` accepts a range plus an ordinary one-parameter or zero-parameter lambda.
Both direct-bracket and parenthesized calls must work.
The owner also authorized the matching while design: a reserved callback loop
with a zero-argument Bool predicate and zero-argument Unit action.

## Goal

Complete source-to-JVM support with reusable range values, exact callback
contracts, safe termination, lifecycle/cancellation integration and regression
coverage. Implementation is complete as of 2026-09-11; validation and review
evidence is recorded in the callback-loop backend closeout records.

## Implementation steps

1. Add range tokens, grammar descriptors, immutable syntax and negative grammar tests.
2. Add the range type, contextual typing and built-in callback specialization.
3. Extend semantic flow, immutable IR, validation and direct JVM emission.
4. Extend runtime ABI, metadata, persistence and editor tooling as applicable.
5. Update normative specifications, independent generators/oracles, coverage,
   knowledge and changelog; run core and extended validation and review the diff.
6. Commit the completed unit. Any incomplete checkpoint must be explicitly WIP.

## Settled behavior

- `..` excludes the end; `...` includes it only when reached by the step.
- Bounds and step evaluate once, left-to-right, when constructing the range.
- Iteration creates a fresh traversal, passes successive values to `|x| body`,
  or invokes `|| body` without arguments; callbacks and iter return Unit.
- Existing unary negation syntax supplies negative steps: `:(- 1)`.
- Zero step fails; wrong-direction ranges are empty; terminal increments must
  not overflow. Captured parameters retain the value of their invocation.

## Open implementation decision

Initial signed-only versus signed-and-unsigned range domains was asked
asynchronously. Unsigned descent needs a separate signed-step contract.
The current implementation uses signed domains as the announced default.

The owner confirmed that `iter` is reserved like `match` on 2026-09-10.
`::iter[...]` begins a new expression, including after a range initializer and
across a newline. It cannot be a user binding, bare value or namespace member.
Both iter and while now execute through the typed JVM backend.

## Progress

- While keyword/grammar boundaries, parser replay, editor highlighting, exact
  callbacks, predicate effects and JVM execution are implemented.
- Reserved iter boundaries, contextual callback specialization, repeated-effect
  analysis, IR certification and primitive JVM traversal are implemented.
- Range tokens, grammar/AST, signed type contracts, typed IR and concrete runtime
  representation are implemented in the worktree.
- Focused grammar/parser tests and RangeIntegrationTest pass, including Java
  exports, stored/inferred ranges, width variants, zero-step failure, extreme
  successor checks, rejected domains and a seeded independent traversal model.
- The first full suite reached 1,079 compiler tests with one failure: the sealed
  IR inventory needed a range source fixture. That fixture is now added and its
  focused test passes. Subsequent full suites passed, including the latest
  source, corpus, grammar and nested-bound changes (`mvn -q test`, exit 0).
- Extended LanguageFuzzTest passed four seeds at 1,800 cases each. The corrected
  range property campaign also passed at 1,800 samples per signed width (7,200
  range cases), including the latest nested-bound test.
- Completed backend closeout adds fixed-point callable/capture/aggregate transfer,
  post-loop binding facts, constant-stack bytecode and cooperative safe points,
  exact-width host arguments, range storage/snapshots, source-mapped failures,
  source loop fuzz/oracles, and execution/cancellation regressions. The extended
  campaign includes four source seeds at 1,800 cases each, 1,800 range samples
  per signed width and 1,800 executed loop samples per signed width.

## Validation

Positive/negative grammar and type checks, numeric endpoints/overflow/zero step,
nested and reused ranges, callback arity/capture/effects, source-mapped runtime
failures, Java ABI and persistent session behavior, independent fuzz oracle,
ordinary `mvn test` and an extended language campaign.
