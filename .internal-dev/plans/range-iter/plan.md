# First-class ranges and iter

## Context

The owner authorized first-class `(start..end:step)` / `(start...end:step)`
ranges and a built-in higher-order function, subsequently named `iter`.
`iter` accepts a range plus an ordinary one-parameter or zero-parameter lambda.
Both direct-bracket and parenthesized calls must work.

## Goal

Complete source-to-JVM support with reusable range values, exact callback
contracts, safe termination, lifecycle/cancellation integration and regression
coverage. This plan is in progress; no implementation completion is claimed.

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

An additional grammar decision is pending: ordinary postfix parsing consumes
`let r = (0..10:1) ::iter[r || ()]` as a method call on the range initializer,
including across a newline. The owner was asked whether `iter` should be reserved
(like `match`, permitting a direct-call boundary exception) or remain shadowable
with explicit form boundaries. Do not decide this public spelling/namespace rule
silently. Implementation of iter is not complete.

## Progress

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
- Remaining: iter name policy, complete callback specialization and repeated
  effect/capture analysis, bytecode loop and safe points, persistence/snapshots,
  broader range nesting/provenance and source failure checks, source fuzz
  integration, extended campaign, documentation review and final completion.

## Validation

Positive/negative grammar and type checks, numeric endpoints/overflow/zero step,
nested and reused ranges, callback arity/capture/effects, source-mapped runtime
failures, Java ABI and persistent session behavior, independent fuzz oracle,
ordinary `mvn test` and an extended language campaign.
