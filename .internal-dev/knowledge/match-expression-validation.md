# Match expression validation

## Topic
Cross-phase and independent-test pitfalls when adding value and conditional matching.

## Source References
- specifications/language-core.md, Match expressions
- reviews/match-expressions-independent-review.md
- lyra-compiler/src/test/java/io/mindspice/lyra/compiler/backend/jvm/MatchBoundaryIntegrationTest.java
- lyra-repl/src/test/java/io/mindspice/lyra/repl/MatchSessionTest.java
- lyra-compiler/src/test/java/io/mindspice/lyra/compiler/conformance/TypedProgramGenerator.java

## Key Takeaways
- Match needs an explicit subject-once representation through typing/flow/IR. Do not duplicate subject expressions when lowering branch chains.
- Pattern/guard parsing must distinguish arm arrows from namespace arrows while still permitting namespace values/calls followed by postfix indexing/member access. Test `expected -> ::answer[]` and `module->:.values[0] -> result` together.
- Language contract version 2 removed the `??` arm marker and the wildcard-subject conditional mode, and added the parenthesized-only `cond` form. Match arms are contiguous: after an arm's result the next arm begins immediately, and the only accepted separator is a comma before an arm whose head begins with `::`. The arm-boundary lookahead (`matchHeadEndsAtArmBoundary`) therefore no longer has a marker token to anchor on and must accept `when` or the arm arrow; keep the namespace-arrow versus arm-arrow distinction and the postfix-chain cases covered.
- `_` in the subject position is now the obsolete conditional form (`LYC-PARSE-013`), not conditional mode; `cond` replaces it. The subject-start offset differs by surface form: `opening + 1` for the bracket spellings and `keyword + 1` for the parenthesized form. Getting this backwards fails grammar-program validation with a confusing "match requires a value subject" invariant instead of a parse diagnostic.
- A cond expression shares the match IR with `TypedMatch.MatchMode.CONDITIONAL` but has its own AST node and `TypedExpressionKind.COND`; `TypedSemanticGraph` requires `COND iff mode == CONDITIONAL` and `(MATCH || COND) iff match metadata present`. Adding the kind requires updating every exhaustive `TypedExpressionKind` switch and every match-like syntax traversal: flow/provenance analyzers, callable summaries, nominal initialization, retained transfer/route graphs, IR lowering/evaluation-order validation, callback-lambda discovery, and structural-context/type-synthesis helpers. A direct execution test alone does not cover these certificate and contextual-shape paths.
- Namespace paths permit both `game->constants->:.value` and `game->constants:.value`. The terminal arrow is optional in the AST; lookahead, descriptor validation, parser replay, and spans must agree, including match patterns/guards with postfix chains.
- Only a standalone underscore is wildcard syntax. An underscore-rooted value expression such as `_[0]` remains an ordinary expression; wildcard position must not swallow its postfix operators.
- Same-width unsigned switch tests do not prove logical widening in general comparison paths. Add U8-to-I16, U16-to-I32, and U32-to-I64/F64 comparisons using high-bit Java carrier values.
- Direct result typing and structural shape synthesis must agree. Numeric literal context inside a match nested in an inferred tuple exposed a separate synthesis bug even though direct match tests passed.
- Fuzz renderings must be checked independently: an early conditional bracket generator omitted its `_` subject and emitted duplicate fallbacks. The corrected renderer and model are covered by dedicated tests.
- Nilable subjects may be compared only with #NIL before explicit narrowing under the existing equality contract. Do not generate a nonnil value pattern against a nilable subject and call it valid match coverage.
- Parentheses are calls, not grouping, with one deliberate exception: the exact parenthesized direct call `(::function[args])` now preserves that direct call instead of invoking its result, and the grammar wraps it in `PARENTHESIZED_DIRECT_CALL` so the parentheses stay inside a consumable token range. `(::function[args] extra)` is still an ordinary callable call on the direct call's result.
- The obsolete special-form spellings `::match[...]`, `::cond[...]`, `::iter[...]` and `::while[...]` must be rejected in *every* position, including qualified (`ns->::match[...]`) and receiver-postfix (`x::match[...]`) readings, with `LYC-PARSE-012`. The receiver case used to return the base expression and let the next `::` start a fresh form; that "fresh expression" reading is gone, so the postfix, namespace-suffix, and unqualified-direct-call paths all need the explicit rejection together.
- Adding a tenth rotating fuzz family requires at least 100 cases to preserve all ten numeric kinds; a 90-case minimum covered all families but missed a numeric kind. Explicit inventory evidence protects this requirement.

## Project Relevance
These checks preserve the existing type/flow/sealing contracts while keeping the generated matcher lazy and allocation-free on primitive paths. Broad test success alone did not detect cross-width unsigned conversion or structural inference gaps; permanent regressions now exercise both.

## Open Questions
None for the implemented scope. Root independently passed `mvn test` (1,445 tests, zero failures/errors, two opt-in GUI skips) and a five-seed extended campaign (9,000 cases) after all repairs. See reviews/match-expressions-independent-review.md for evidence and preserved regression locations.
