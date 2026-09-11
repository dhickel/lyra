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
- Pattern/guard parsing must distinguish arm arrows from namespace arrows while still permitting namespace values/calls followed by postfix indexing/member access. Test `?? expected -> ::answer[]` and `?? module->:.values[0] -> result` together.
- Reserved `::match` must start a new expression after a call target or preceding argument rather than entering receiver-method postfix parsing. Existing conditional arrows before `::match` likewise are not namespace qualification. Both cases need explicit regressions.
- Namespace paths permit both `game->constants->:.value` and `game->constants:.value`. The terminal arrow is optional in the AST; lookahead, descriptor validation, parser replay, and spans must agree, including match patterns/guards with postfix chains.
- Only a standalone underscore is wildcard syntax. An underscore-rooted value expression such as `_[0]` remains an ordinary expression; wildcard position must not swallow its postfix operators.
- Same-width unsigned switch tests do not prove logical widening in general comparison paths. Add U8-to-I16, U16-to-I32, and U32-to-I64/F64 comparisons using high-bit Java carrier values.
- Direct result typing and structural shape synthesis must agree. Numeric literal context inside a match nested in an inferred tuple exposed a separate synthesis bug even though direct match tests passed.
- Fuzz renderings must be checked independently: an early conditional bracket generator omitted its `_` subject and emitted duplicate fallbacks. The corrected renderer and model are covered by dedicated tests.
- Nilable subjects may be compared only with #NIL before explicit narrowing under the existing equality contract. Do not generate a nonnil value pattern against a nilable subject and call it valid match coverage.
- Parentheses are calls, not grouping: `(::function[])` tries to invoke the returned value. Use `(function)` or `::function[]` as appropriate.
- Adding a tenth rotating fuzz family requires at least 100 cases to preserve all ten numeric kinds; a 90-case minimum covered all families but missed a numeric kind. Explicit inventory evidence protects this requirement.

## Project Relevance
These checks preserve the existing type/flow/sealing contracts while keeping the generated matcher lazy and allocation-free on primitive paths. Broad test success alone did not detect cross-width unsigned conversion or structural inference gaps; permanent regressions now exercise both.

## Open Questions
None for the implemented scope. Root independently passed `mvn test` (1,445 tests, zero failures/errors, two opt-in GUI skips) and a five-seed extended campaign (9,000 cases) after all repairs. See reviews/match-expressions-independent-review.md for evidence and preserved regression locations.
